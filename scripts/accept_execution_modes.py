"""Run all three executors against the real provider, keeping only debug run artifacts."""
import argparse
import json
from pathlib import Path
import time
from platform_client import PlatformClient

parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://localhost:9900')
parser.add_argument('--modes', nargs='+', default=['react','plan_execute_replan','workflow'], choices=['react','plan_execute_replan','workflow'])
args = parser.parse_args()
client = PlatformClient(args.base)
folder = Path('target/execution-mode-acceptance')
folder.mkdir(parents=True, exist_ok=True)
agents = client.call('GET', '/api/platform/agents')
oncall = next(a for a in agents if a['config']['strategy'] == 'workflow')
assert oncall['config']['strategy'] == 'workflow'
created = []
pending = []
try:
    for mode in args.modes:
        config = dict(oncall['config'])
        config.update(name='执行模式验收 ' + mode, description='temporary-execution-mode-acceptance',
                      strategy=mode, timeoutSeconds=180, maxToolCalls=10)
        if mode != 'workflow':
            config['instructions'] = '根据用户当前请求，使用知识库和允许的工具回答问题。只解释文档时不要求提供事故现场；不编造事实或根因，操作必须有来源。'
        agent = client.call('POST', '/api/platform/admin/agents', config)
        created.append(agent['id'])
        cases = [('', '香港现在的日期和时间是什么？')] if mode == 'react' else (
            [('', '从知识库定位 error rate SLO 告警的排查文档，读原文后说明建议检查及前提。不要推断本次根因。')]
            if mode == 'plan_execute_replan' else [
                ('error_slo_violation_001', '分析当前告警，说明观察、待验证原因、有来源的检查和前提。'),
                ('postgresql_disk_space_001', '分析当前 PostgreSQL 磁盘空间告警，说明观察、有来源的检查和待确认项。')])
        for scenario, question in cases:
            session = client.call('POST', '/api/platform/sessions', {'agentId': agent['id'], 'origin': 'debug'})
            run = client.call('POST', '/api/platform/runs', {'agentId': agent['id'], 'sessionId': session['id'],
                'question': question, 'scenarioId': scenario or None, 'diagnose': mode == 'workflow'})
            pending.append((mode, run['id']))
    until = time.monotonic() + 205
    summaries = []
    while pending and time.monotonic() < until:
        for mode, run_id in pending[:]:
            run = client.call('GET', '/api/platform/runs/' + run_id)
            if run['status'] in ('queued', 'running', 'reviewing'):
                continue
            events, after = [], 0
            while True:
                page = client.call('GET', f'/api/platform/runs/{run_id}/events?after={after}')
                events.extend(page)
                if len(page) < 200:
                    break
                after = page[-1]['sequence']
            usage = client.call('GET', f'/api/platform/runs/{run_id}/usage')
            (folder / (run_id + '.json')).write_text(json.dumps({'run': run, 'events': events, 'usage': usage}, ensure_ascii=False, indent=2), encoding='utf8')
            result = run.get('result') or {}
            summary = {'mode': mode, 'id': run_id, 'status': run['status'], 'elapsedMs': result.get('elapsedMs'),
                       'toolCalls': result.get('toolCalls'), 'findings': len(result.get('answer', {}).get('findings', [])),
                       'actions': len(result.get('answer', {}).get('actions', [])), 'notices': result.get('notices'), 'error': run.get('error')}
            summaries.append(summary)
            if mode == 'plan_execute_replan':
                stopped = next((e['data'] for e in events if e['type']=='plan_stopped'), {})
                summary['planStop'] = stopped
            print(json.dumps(summary, ensure_ascii=False), flush=True)
            pending.remove((mode, run_id))
        if pending:
            time.sleep(2)
    (folder / 'summary.json').write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding='utf8')
    assert not pending, 'Runs did not terminate'
    assert all(s['status'] in ('completed', 'partial') for s in summaries), 'Inspect saved real-provider artifacts'
finally:
    for _, run_id in pending:
        client.call('POST', '/api/platform/runs/' + run_id + '/cancel')
    for agent_id in created:
        current = client.call('GET', '/api/platform/agents/' + agent_id)
        assert current['config']['description'] == 'temporary-execution-mode-acceptance'
        client.call('DELETE', '/api/platform/admin/agents/' + agent_id + '/sessions')
        client.call('DELETE', '/api/platform/admin/agents/' + agent_id)
