"""Exercise the knowledge/answer policy with the real provider; preserve artifacts, clean only owned debug agents."""
import argparse
import json
from pathlib import Path
import time
from platform_client import PlatformClient

parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://localhost:9900')
parser.add_argument('--cases', nargs='*')
args = parser.parse_args()
client = PlatformClient(args.base)
artifact = Path('target/knowledge-upgrade-acceptance')
artifact.mkdir(parents=True, exist_ok=True)
marker = 'temporary-knowledge-upgrade-acceptance'
base = next(a['config'] for a in client.call('GET', '/api/platform/agents') if a['config']['strategy'] == 'workflow')
created, pending, summaries = [], [], []
failures = []
def expect(condition, message):
    if not condition:
        failures.append(message)
        print('CHECK FAILED: ' + message, flush=True)
cases = [
    ('greeting', 'react', True, '你好', None),
    ('open_on', 'react', True, '探讨一下agent的前景', None),
    ('development_on', 'react', True, '如何看待ai应用开发', None),
    ('open_off', 'react', False, '探讨一下agent的前景', None),
    ('internal', 'react', True, '我们公司生产环境当前到底部署了多少台服务器？请给出实际数量。', None),
    ('sources_only', 'react', True, '仅根据绑定的知识库回答：如何看待ai应用开发？资料没说就说明没找到，不能用通识补充。', None),
    ('plan_open', 'plan_execute_replan', True, '如何看待ai应用开发', None),
    ('small_document', 'react', True, '定位 error rate SLO 告警的排查文档，读取完整原文，说明告警含义和文档给出的建议。不要推断本次根因。', None),
    ('plan_document', 'plan_execute_replan', True, '定位 error rate SLO 告警的排查文档，读取完整原文，说明建议和前提。不要推断本次根因。', None),
    ('workflow', 'workflow', False, '分析当前 PostgreSQL 磁盘空间告警，说明观察、有来源的检查和待确认项。', 'postgresql_disk_space_001'),
]
try:
    for name, mode, enabled, question, scenario in cases:
        if args.cases and name not in args.cases:
            continue
        config = dict(base)
        config.update(name='升级验收 ' + name, description=marker, strategy=mode,
                      allowGeneralKnowledge=enabled, timeoutSeconds=180, maxToolCalls=10,
                      instructions='优先参考相关资料，按配置允许通识解释和分析。内部事实、实时信息和指定文档内容必须查证。不要编造来源或根因。')
        agent = client.call('POST', '/api/platform/admin/agents', config)
        created.append(agent['id'])
        assert agent['config']['allowGeneralKnowledge'] == enabled
        session = client.call('POST', '/api/platform/sessions', {'agentId': agent['id'], 'origin': 'debug'})
        run = client.call('POST', '/api/platform/runs', {'agentId': agent['id'], 'sessionId': session['id'], 'question': question,
                         'scenarioId': scenario, 'diagnose': mode == 'workflow'})
        pending.append(run['id'])
        deadline = time.monotonic() + 195
        while run['status'] in ('queued', 'running', 'reviewing') and time.monotonic() < deadline:
            time.sleep(1)
            run = client.call('GET', '/api/platform/runs/' + run['id'])
        assert run['status'] not in ('queued', 'running', 'reviewing'), name + ': did not terminate'
        pending.remove(run['id'])
        events, after = [], 0
        while True:
            page = client.call('GET', f"/api/platform/runs/{run['id']}/events?after={after}")
            events.extend(page)
            if len(page) < 200:
                break
            after = page[-1]['sequence']
        result = run.get('result') or {}
        usage = client.call('GET', f"/api/platform/runs/{run['id']}/usage")
        (artifact / (name + '.json')).write_text(json.dumps({'run': run, 'events': events, 'usage': usage}, ensure_ascii=False, indent=2), encoding='utf8')
        tools = [e['data']['id'] for e in events if e['type'] == 'tool_start']
        general = result.get('answer', {}).get('generalExplanation', '')
        routing = next((e['data'] for e in events if e['type'] == 'routing'), {})
        summary = dict(case=name, status=run['status'], elapsedMs=result.get('elapsedMs'), toolCalls=result.get('toolCalls'),
                       generalChars=len(general), basis=routing.get('basis'), tools=tools, error=run.get('error'))
        summaries.append(summary)
        print(json.dumps(summary, ensure_ascii=False), flush=True)
        expect(run['status'] != 'failed', name + ': ' + str(run.get('error')))
        if name == 'greeting':
            expect(run['status'] == 'completed' and not tools, name + ': no retrieval for greetings')
            expect(any(e['type'] == 'answer_delta' for e in events), name + ': provider SSE deltas')
        if name in ('open_on', 'development_on', 'plan_open'):
            expect(run['status'] == 'completed' and len(general) > 60, name + ': expected useful general answer')
            expect(bool(tools) and tools[0] == 'knowledge.search', name + ': initial lookup required')
            expect(sum(t in ('knowledge.search', 'documents.search', 'documents.find') for t in tools) <= 3, name + ': search bound')
            expect('documents.sections' not in tools, name + ': unnecessary section lookup')
        if name in ('open_off', 'internal', 'sources_only'):
            expect(not general, name + ': general knowledge must be disabled')
            expect(run['status'] == 'insufficient_evidence', name + ': expected honest missing evidence')
        if name in ('small_document', 'plan_document'):
            expect(run['status'] in ('completed', 'partial') and result.get('answer', {}).get('findings'), name + ': expected sourced answer')
            expect('documents.read' in tools and 'documents.sections' not in tools, name + ': direct full reading')
        if name == 'workflow':
            expect(result.get('kind') == 'incident_report' and result.get('answer', {}).get('findings'), name + ': incident report')
            expect(all(label in result.get('text', '') for label in ('现场概况', '当前判断', '建议操作', '待确认项')), name + ': fixed format')
finally:
    (artifact / 'summary.json').write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding='utf8')
    for run_id in pending:
        client.call('POST', '/api/platform/runs/' + run_id + '/cancel')
    for agent_id in created:
        current = client.call('GET', '/api/platform/agents/' + agent_id)
        assert current['config']['description'] == marker
        client.call('DELETE', '/api/platform/admin/agents/' + agent_id + '/sessions')
        client.call('DELETE', '/api/platform/admin/agents/' + agent_id)
assert not failures, '; '.join(failures)
