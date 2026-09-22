const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');

const staticDir=path.join(__dirname,'../../main/resources/static');
const consoleSource=fs.readFileSync(path.join(staticDir,'platform-console.js'),'utf8');
const css=fs.readFileSync(path.join(staticDir,'platform.css'),'utf8');

test('agent results open a dedicated per-case report',()=>{
  assert.match(consoleSource,/data-action="eval-report"/);
  assert.match(consoleSource,/function renderAgentCaseReport\(/);
  assert.match(consoleSource,/AGENT CASE REPORT/);
  assert.match(consoleSource,/实际问题/);
  assert.match(consoleSource,/参考答案 \/ 验收标准/);
  assert.match(consoleSource,/Agent 实际回答/);
});

test('report case count and direct links come from the saved job manifest',()=>{
  assert.match(consoleSource,/function reportManifestCases\(job,suite\)/);
  assert.match(consoleSource,/manifest\?\.cases/);
  assert.match(consoleSource,/rows\.length/);
  assert.match(consoleSource,/evalCase=\$\{encodeURIComponent\(caseId\)\}/);
});

test('report exposes recognizable case-level quality dimensions',()=>{
  for(const label of ['Task Success','Answer Correctness','Faithfulness','Tool Accuracy']){
    assert.ok(consoleSource.includes(label),`${label} must be visible in the report`);
  }
  assert.match(consoleSource,/实际耗时/);
  assert.match(consoleSource,/Token/);
  assert.match(consoleSource,/模型与用量/);
});

test('workflow reports show a concise frozen incident snapshot',()=>{
  assert.match(consoleSource,/function renderReportIncidentSnapshot\(label\)/);
  assert.match(consoleSource,/if\(!snapshot\)return ''/);
  assert.match(consoleSource,/评测时冻结快照/);
  assert.match(consoleSource,/告警摘要/);
  assert.match(consoleSource,/日志时间线/);
  assert.match(consoleSource,/查看完整现场数据/);
  assert.match(consoleSource,/renderReportIncidentSnapshot\(label\)/);
});

test('report uses two columns on desktop and a stacked mobile layout',()=>{
  assert.match(css,/\.eval-report-layout\{[^}]*grid-template-columns:250px minmax\(0,1fr\)/);
  assert.match(css,/@media\(max-width:620px\)[\s\S]*\.eval-report-layout\{grid-template-columns:1fr/);
  assert.match(css,/\.eval-report-case-list\{display:flex;max-height:none;overflow:auto/);
});
