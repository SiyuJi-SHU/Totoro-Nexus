"""Verify ReAct/Plan follow-ups reuse only the current session's archived evidence."""
import argparse
import json
from pathlib import Path
import time

from platform_client import PlatformClient

parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://localhost:9900')
args = parser.parse_args()
client = PlatformClient(args.base)
folder = Path('target/conversation-context-acceptance')
folder.mkdir(parents=True, exist_ok=True)
marker = 'temporary-conversation-context-acceptance'
agents = client.call('GET', '/api/platform/agents')
created = []


def finish(run):
    deadline = time.monotonic() + 195
    while run['status'] in ('queued', 'running', 'reviewing'):
        assert time.monotonic() < deadline, 'run did not terminate'
        time.sleep(1)
        run = client.call('GET', '/api/platform/runs/' + run['id'])
    events = client.call('GET', '/api/platform/runs/' + run['id'] + '/events')
    usage = client.call('GET', '/api/platform/runs/' + run['id'] + '/usage')
    (folder / (run['id'] + '.json')).write_text(
        json.dumps({'run': run, 'events': events, 'usage': usage}, ensure_ascii=False, indent=2), encoding='utf8')
    return run, events


try:
    for mode in ('react', 'plan_execute_replan'):
        base = next(a['config'] for a in agents if a['config']['strategy'] == mode)
        config = dict(base)
        config.update(name='上下文验收 ' + mode, description=marker, timeoutSeconds=180, maxToolCalls=10)
        agent = client.call('POST', '/api/platform/admin/agents', config)
        created.append(agent['id'])

        session = client.call('POST', '/api/platform/sessions', {'agentId': agent['id'], 'origin': 'debug'})
        first, _ = finish(client.call('POST', '/api/platform/runs', {
            'agentId': agent['id'], 'sessionId': session['id'],
            'question': '定位 error rate SLO 告警的排查文档，读原文后说明告警含义和第一条检查建议。不要推断本次根因。'}))
        follow, follow_events = finish(client.call('POST', '/api/platform/runs', {
            'agentId': agent['id'], 'sessionId': session['id'],
            'question': '刚才第一条检查建议是什么？只解释它的资料依据，不要搜索新资料。'}))

        isolated = client.call('POST', '/api/platform/sessions', {'agentId': agent['id'], 'origin': 'debug'})
        separate, separate_events = finish(client.call('POST', '/api/platform/runs', {
            'agentId': agent['id'], 'sessionId': isolated['id'],
            'question': '刚才第一条检查建议是什么？只解释它的资料依据，不要搜索新资料。'}))

        follow_tools = [e['data']['id'] for e in follow_events if e['type'] == 'tool_start']
        assert first['status'] == 'completed' and first['result']['evidence']
        assert follow['status'] == 'completed'
        assert any(e['type'] == 'history_context' for e in follow_events)
        assert 'knowledge.search' not in follow_tools and 'documents.search' not in follow_tools
        assert not any(e['type'] == 'history_context' for e in separate_events)
        summary = {'mode': mode, 'first': first['status'], 'follow': follow['status'],
                   'followTools': follow_tools, 'isolated': separate['status'],
                   'isolatedHistory': False, 'firstMs': first['result']['elapsedMs'],
                   'followMs': follow['result']['elapsedMs']}
        print(json.dumps(summary, ensure_ascii=False), flush=True)
finally:
    for agent_id in created:
        current = client.call('GET', '/api/platform/agents/' + agent_id)
        assert current['config']['description'] == marker
        client.call('DELETE', '/api/platform/admin/agents/' + agent_id + '/sessions')
        client.call('DELETE', '/api/platform/admin/agents/' + agent_id)
