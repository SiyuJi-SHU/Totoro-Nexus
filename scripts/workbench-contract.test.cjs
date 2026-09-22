const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/platform-workbench.js','utf8');
const formatContext=vm.createContext({});
vm.runInContext(fs.readFileSync('src/main/resources/static/platform-common.js','utf8')+'\nthis.format=Platform.inlineMarkdown;',formatContext);
const tick=()=>new Promise(resolve=>setImmediate(resolve));
async function mount(mode='workflow'){
  const elements=new Map(),handlers={},calls=[];
  const element=id=>{if(!elements.has(id))elements.set(id,{value:'',innerHTML:'',textContent:'',hidden:false,disabled:false,focus(){},insertAdjacentHTML(){}});return elements.get(id);};
  const current={id:'agent',version:2,enabled:true,config:{name:'Test',strategy:mode,schemaVersion:2}};
  const old={...current,version:1,config:{...current.config,schemaVersion:0}};
  const api=async(url,options)=>{calls.push({url,options});
    if(url==='/api/platform/agents')return [current];
    if(url.startsWith('/api/platform/sessions?'))return [{id:'old-session',agentVersion:1,title:'History'}];
    if(url==='/api/platform/sessions/old-session')return [];
    if(url==='/api/platform/agents/agent?version=1')return old;
    if(url.endsWith('/attachments'))return [];
    throw Error(url);
  };
  const context={Platform:{$:element,esc:s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),api,auth:async()=>({role:'MEMBER'}),toast(){},fail:e=>{throw e;},status(){},time(){},details(){},dialog(){}},
    URLSearchParams,location:{search:''},history:{replaceState(){}},document:{querySelectorAll:()=>[],addEventListener:(name,handler)=>{handlers[name]=handler;}},
    localStorage:{getItem:()=>null},console,requestAnimationFrame:cb=>cb(),setTimeout,clearTimeout};
  context.Platform.inlineMarkdown=formatContext.format;
  vm.runInNewContext(source.replace('  init().catch(fail);','  globalThis.reportForTest=reportHtml;\n  init().catch(fail);'),context);await tick();
  const click=(selector,data)=>handlers.click({target:{closest:query=>query===selector?{dataset:data}:null}});
  return {element,calls,click,report:context.reportForTest};
}
test('workflow shortcuts fill both fields without submitting',async()=>{
  const ui=await mount();assert.equal(ui.element('#simulationShortcuts').hidden,false);
  await ui.click('[data-simulation]',{simulation:'postgres'});
  assert.match(ui.element('#question').value,/PostgreSQL/);assert.match(ui.element('#incidentText').value,/模拟材料/);
  assert.equal(ui.element('#scenario').value,'');assert.equal(ui.calls.some(c=>c.options?.method==='POST'),false);
});
test('generic modes hide incident controls and simulation shortcuts',async()=>{
  for(const mode of ['react','plan_execute_replan']){const ui=await mount(mode);assert.equal(ui.element('#incidentInput').hidden,true);assert.equal(ui.element('#diagnose').hidden,true);assert.equal(ui.element('#simulationShortcuts').hidden,true);}
});
test('even an empty old session is read only and new conversation restores input',async()=>{
  const ui=await mount();await ui.click('[data-session]',{session:'old-session'});await tick();
  assert.equal(ui.element('#historyNotice').hidden,false);assert.equal(ui.element('#send').disabled,true);assert.equal(ui.element('#uploadAttachmentBtn').disabled,true);
  await ui.element('#newSession').onclick();assert.equal(ui.element('#historyNotice').hidden,true);assert.equal(ui.element('#send').disabled,false);
});
test('fixed reports escape source text and fold duplicate procedure context without dropping commands',async()=>{
  const ui=await mount();const citation={id:'L1',quote:'<script>alert(1)</script>'};
  const action={text:'手册中的操作说明（原文）：\n完整前提 <img src=x onerror=alert(1)>',prerequisites:'需要确认权限',citations:[citation]};
  const html=ui.report({kind:'incident_report',incident:{scenarioName:'真实材料',scenarioId:'user-materials'},evidence:[{id:'L1'}],answer:{
    findings:[{text:'观测异常',certainty:'observation',citations:[citation]}],actions:[{...action,command:'select 1'},{...action,command:'select 2'}],missingEvidence:['补充时间'],contradictions:[]}},'run');
  assert.match(html,/现场概况/);assert.match(html,/当前判断/);assert.match(html,/建议操作/);assert.match(html,/待确认项/);
  assert.match(html,/用户提交材料/);assert.doesNotMatch(html,/<script>|<img /);assert.match(html,/&lt;script&gt;/);
  assert.equal((html.match(/查看完整来源与适用条件/g)||[]).length,1);assert.match(html,/<pre>select 1<\/pre>/);assert.match(html,/<pre>select 2<\/pre>/);
});

test('report service comes from the actual snapshot JSON, with a safe missing-data fallback',async()=>{
  const ui=await mount();
  const report=alertsJson=>ui.report({kind:'incident_report',incident:{scenarioName:'本次现场',scenarioId:'user-materials',alertsJson},answer:{},evidence:[]},'run');
  assert.match(report('{"alerts":[{"service":"postgres-primary"}]}'),/postgres-primary/);
  assert.match(report('{"alerts":[{"service":"<img src=x>"}]}'),/&lt;img src=x&gt;/);
  for(const json of ['{"alerts":[]}','invalid',undefined])assert.match(report(json),/待确认服务/);
});
