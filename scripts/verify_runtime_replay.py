"""Verify greetings, cancellation and durable SSE replay against a running platform.

Creates only debug sessions; never prints credentials or changes existing agents.
"""
import argparse
import json
import time
import urllib.request
from pathlib import Path
from platform_client import PlatformClient

parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://localhost:9900')
parser.add_argument('--output', default='target/upgrade-live/runtime-replay-audit.json')
args = parser.parse_args()
client = PlatformClient(args.base)


def wait(run):
    deadline = time.monotonic() + 15
    while run['status'] in ('queued', 'running', 'reviewing'):
        assert time.monotonic() < deadline, 'Quick lifecycle check did not finish'
        time.sleep(.1)
        run = client.call('GET', '/api/platform/runs/' + run['id'])
    return run


def stream(run_id, last=None):
    req = urllib.request.Request(client.base + '/api/platform/runs/' + run_id + '/stream')
    if last is not None:
        req.add_header('Last-Event-ID', str(last))
    with client.opener.open(req, timeout=15) as response:
        frames = response.read().decode('utf-8').replace('\r\n', '\n').split('\n\n')
    ids = [int(line[3:].strip()) for frame in frames for line in frame.splitlines() if line.startswith('id:')]
    assert any('event:done' in frame.replace('event: ', 'event:') for frame in frames)
    return ids


records = []
for agent in client.call('GET', '/api/platform/agents'):
    if not agent['enabled']:
        continue
    session = client.call('POST', '/api/platform/sessions', {'agentId': agent['id'], 'origin': 'debug'})
    run = wait(client.call('POST', '/api/platform/runs', {'agentId': agent['id'], 'sessionId': session['id'], 'question': 'hello'}))
    assert run['status'] == 'completed' and run['result']['kind'] == 'chat_reply'
    assert run['result']['toolCalls'] == 0
    usage = client.call('GET', '/api/platform/runs/' + run['id'] + '/usage')
    assert {u['purpose'] for u in usage} == {'task-routing', 'direct-response'}
    events = client.call('GET', '/api/platform/runs/' + run['id'] + '/events')
    ids = stream(run['id'])
    assert ids == [e['sequence'] for e in events]
    assert stream(run['id'], ids[0]) == ids[1:]
    assert stream(run['id'], ids[-1]) == []
    records.append({'agentId': agent['id'], 'strategy': agent['config']['strategy'], 'runId': run['id'], 'version': run['agentVersion'], 'kind': run['result']['kind'], 'toolCalls': 0, 'providerCalls': len(usage), 'fullReplay': ids, 'reconnectReplayVerified': True})

agent_id = records[0]['agentId']
session = client.call('POST', '/api/platform/sessions', {'agentId': agent_id, 'origin': 'debug'})
run = client.call('POST', '/api/platform/runs', {'agentId': agent_id, 'sessionId': session['id'], 'question': '请详细解释 Apdex 的定义、阈值与排查方法。'})
cancelled = client.call('POST', '/api/platform/runs/' + run['id'] + '/cancel')
assert cancelled['status'] == 'cancelled'
followup = wait(client.call('POST', '/api/platform/runs', {'agentId': agent_id, 'sessionId': session['id'], 'question': 'hello'}))
assert followup['status'] == 'completed'
assert client.call('GET', '/api/platform/runs/' + run['id'])['status'] == 'cancelled'
assert len([e for e in client.call('GET', '/api/platform/runs/' + run['id'] + '/events') if e['type'] == 'terminal']) == 1
normal = client.call('GET', '/api/platform/sessions?agentId=' + agent_id)
assert session['id'] not in {s['id'] for s in normal}
result = {'base': args.base, 'greetings': records, 'cancelledRunId': run['id'], 'subsequentRunId': followup['id'], 'cancellationRemainsTerminal': True, 'debugExcludedFromNormalHistory': True}
output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Verified {len(records)} Agent greetings and SSE replay; cancellation and session recovery passed.')
