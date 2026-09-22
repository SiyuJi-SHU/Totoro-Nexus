(() => {
  const {$,esc,api,auth,toast,fail,status,time,details,dialog}=Platform;
  let me,agents=[],agent,sessionId=null,activeRun=null,stream=null,scenes=[],knownRuns=new Map(),attachments=[],sessionOffset=0,sessionAgent=null,knownSessions=new Map();
  const currentAgent=()=>sessionAgent||agent;
  const readOnly=()=>currentAgent()?.config.schemaVersion!==2;
  const active=s=>['queued','running','reviewing'].includes(s);
  const timingEvents=new Map();let timingTimer=null;
  const base='/api/platform';const debug=new URLSearchParams(location.search).get('debug')==='1';const debugVersion=debug?(+new URLSearchParams(location.search).get('version')||null):null;
  function renderPlan(plan){$('#currentPlan').innerHTML=`<div class="panel plan"><h3>执行计划</h3><ol>${plan.map(s=>`<li>${esc(s.title)} <span class="sub">${({pending:'待执行',in_progress:'进行中',completed:'已执行',blocked:'未完成',abandoned:'已停止'})[s.status]||esc(s.status)}</span></li>`).join('')}</ol></div>`;}
  function setBusy(busy){$('#send').disabled=$('#diagnose').disabled=$('#agentSelect').disabled=$('#newSession').disabled=busy;$('#stop').hidden=!busy;$('#stop').disabled=busy&&!activeRun;$('#scenario').disabled=busy;$('#uploadAttachmentBtn').disabled=busy;$('#incidentText').disabled=busy||!agent?.enabled||readOnly();$('#send').disabled=$('#diagnose').disabled=$('#uploadAttachmentBtn').disabled=busy||!agent?.enabled||readOnly();document.querySelectorAll('[data-action=deleteAttachment]').forEach(b=>b.disabled=busy||readOnly());}
  async function listSessions(more=false){sessionOffset=more?sessionOffset+50:0;const sessions=await api(base+'/sessions?agentId='+encodeURIComponent(agent.id)+'&offset='+sessionOffset+'&limit=50');sessions.forEach(s=>knownSessions.set(s.id,s));const html=sessions.map(s=>`<button type="button" class="${s.id===sessionId?'active':''}" data-session="${esc(s.id)}" title="${esc(s.title)}">${esc(s.title)}</button>`).join('');if(more)$('#sessions').insertAdjacentHTML('beforeend',html);else $('#sessions').innerHTML=html||'<p class="sub">暂无会话</p>';$('#moreSessions').hidden=sessions.length<50;}

  async function loadAttachments(){if(!sessionId){attachments=[];return;}try{attachments=await api(base+'/sessions/'+sessionId+'/attachments');renderAttachments();}catch(e){attachments=[];console.error('加载附件失败',e);}}
  function renderAttachments(){const container=$('#attachmentList');if(!container)return;if(!attachments.length){container.innerHTML='<p class="sub">暂无附件</p>';return;}container.innerHTML=attachments.map(att=>`<div class="attachment-item"><span>📄 ${esc(att.filename)} (${formatSize(att.fileSize)})</span><button type="button" data-action="download" data-id="${esc(att.id)}">下载</button><button type="button" data-action="deleteAttachment" data-id="${esc(att.id)}">删除</button></div>`).join('');}
  function formatSize(bytes){if(bytes<1024)return bytes+' B';if(bytes<1024*1024)return(bytes/1024).toFixed(1)+' KB';return(bytes/(1024*1024)).toFixed(1)+' MB';}
  async function downloadAttachment(id){try{window.open(base+'/sessions/'+sessionId+'/attachments/'+id,'_blank');}catch(e){fail(e);}}
  async function deleteAttachment(id){if(!confirm('确认删除此附件？'))return;try{await api(base+'/sessions/'+sessionId+'/attachments/'+id,{method:'DELETE'});await loadAttachments();toast('附件已删除');}catch(e){fail(e);}}
  async function uploadAttachment(){if(!sessionId){const session=await api(base+'/sessions',{method:'POST',body:{agentId:agent.id,origin:debug?'debug':'user',agentVersion:debugVersion}});sessionId=session.id;await listSessions();}const input=document.createElement('input');input.type='file';input.accept='.txt,.log,.md,.json';input.onchange=async()=>{const file=input.files[0];if(!file)return;if(file.size>5*1024*1024){toast('文件大小不能超过5MB',true);return;}try{const formData=new FormData();formData.append('file',file);await api(base+'/sessions/'+sessionId+'/attachments',{method:'POST',body:formData});await loadAttachments();toast('附件已上传');}catch(e){fail(e);}};input.click();}
  function agentHeader(selected){
    $('#agentTitle').textContent=selected.config.name;
    $('#greeting').textContent=selected.config.description||'根据配置的知识库、工具与行为模式处理请求。';
    $('#agentVersion').textContent=`AGENT / v${selected.version} · ${({react:'ReAct',workflow:'Workflow',plan_execute_replan:'Plan–Execute–Replan'})[selected.config.strategy]||selected.config.strategy}`;

    const hasIncidentCap=selected.config.strategy==='workflow';
    $('#scenePanel').hidden=!(debug&&me.role==='ADMIN'&&hasIncidentCap);$('#diagnose').hidden=!hasIncidentCap;$('#incidentInput').hidden=!hasIncidentCap;
    $('#simulationShortcuts').hidden=!hasIncidentCap||readOnly();
    $('#historyNotice').hidden=!readOnly();
    $('#uploadLabel').hidden=true;
  }
  async function selectAgent(id){agent=agents.find(a=>a.id===id)||agents[0];if(!agent){$('#messages').innerHTML='<div class="empty">暂无已启用的智能体，请联系管理员。</div>';$('#composer').hidden=true;return;}if(debugVersion)agent=await api(base+'/agents/'+agent.id+'?version='+debugVersion);$('#agentSelect').value=agent.id;history.replaceState(null,'','/?agent='+encodeURIComponent(agent.id)+(debug?'&debug=1'+(debugVersion?'&version='+debugVersion:''):''));await newSession();}
  async function newSession(){if(timingTimer){clearInterval(timingTimer);timingTimer=null;}timingEvents.clear();if(stream){stream.close();stream=null;}sessionId=null;sessionAgent=null;activeRun=null;knownRuns.clear();attachments=[];$('#incidentText').value='';agentHeader(agent);$('#messages').innerHTML=`<div class="empty">${esc(agent.config.greeting||'描述你要查询或分析的问题。')}</div>`;$('#runStage').textContent='';$('#currentPlan').innerHTML='';$('#liveTrace').hidden=true;$('#traceContent').textContent='';renderAttachments();setBusy(false);await listSessions();}
  async function openSession(id){if(timingTimer){clearInterval(timingTimer);timingTimer=null;}activeRun=null;if(stream){stream.close();stream=null;}sessionId=id;sessionAgent=null;const runs=await api(base+'/sessions/'+id);knownRuns=new Map(runs.map(r=>[r.id,r]));$('#messages').innerHTML=runs.map(renderRun).join('');$('#runStage').textContent='';$('#currentPlan').innerHTML='';$('#liveTrace').hidden=true;const last=runs.at(-1);if(last){const config=await api(base+'/agents/'+agent.id+'?version='+last.agentVersion);sessionAgent=config;agentHeader(config);const incident=runs.map(r=>r.incident||r.result?.incident).find(Boolean);if(incident){$('#scenario').value=incident.scenarioId;}}if(!last&&knownSessions.has(id)){sessionAgent=await api(base+'/agents/'+agent.id+'?version='+knownSessions.get(id).agentVersion);agentHeader(sessionAgent);}await loadAttachments();await listSessions();if(last&&active(last.status))watch(last);else setBusy(false);if(last?.result?.plan?.length)renderPlan(last.result.plan);}
  function sceneInfo(){const scene=scenes.find(s=>s.scenario_id===$('#scenario').value);$('#sceneInfo').textContent=scene?.alert?.description||'所有分析使用这份现场，不随候选方向切换。';}
  function incidentFor(run){return run.incident||run.result?.incident;}
  function firstIncident(run){const incident=incidentFor(run);if(!incident)return null;for(const earlier of knownRuns.values()){if(earlier.id===run.id)break;if(incidentFor(earlier)?.id===incident.id)return null;}return incident;}
  function facts(incident){
    if(!incident)return '';let rawAlerts={},rawLogs={};try{rawAlerts=JSON.parse(incident.alertsJson);rawLogs=JSON.parse(incident.logsJson);}catch{}
    const fields={service:'服务',instance:'实例',severity:'级别',current_value:'观测值',threshold:'触发条件',duration:'持续时间',active_at:'触发时间',observed_at:'观测时间',timestamp:'时间',impact:'影响'};
    return `<div class="facts"><strong>${esc(incident.scenarioName)} · ${incident.scenarioId==='user-materials'?'用户提交材料':'模拟现场'}</strong>${(rawAlerts.alerts||[]).map(a=>`<p><b>${esc(a.alert_name)}</b></p><dl class="fact-grid">${Object.entries(fields).filter(([key])=>a[key]!=null&&a[key]!=='').map(([key,label])=>`<dt>${label}</dt><dd>${esc(typeof a[key]==='object'?JSON.stringify(a[key]):a[key])}</dd>`).join('')}</dl>`).join('')}${details('告警与日志原始快照',{capturedAt:incident.capturedAt,alerts:rawAlerts,logs:rawLogs})}</div>`;
  }
  function answerHtml(text,result,runId){return text.split('```').map((piece,i)=>i%2?`<pre>${esc(piece.replace(/^\w*\n/,''))}</pre>`:`<div class="answer-text">${Platform.inlineMarkdown(piece).replace(result.kind==='incident_report'?/^(现场概况|当前判断|反证与限制|建议操作|待确认项)$/gm:/$^/,'<h3>$1</h3>').replace(/\[((?:D-|M-|U-|T-)[a-f0-9]{20}|[AL]\d+)\]/g,(raw,id)=>{const index=(result.evidence||[]).findIndex(e=>e.id===id);return index<0?raw:`<a class="tag" href="#" data-citation="${esc(id)}" data-run="${esc(runId)}">[${index+1}]</a>`;})}</div>`).join('');}
  function reportHtml(result,runId){
    const answer=result.answer||{},incident=result.incident;
    let service=incident?.service;
    if(!service){try{service=JSON.parse(incident?.alertsJson||'{}').alerts?.[0]?.service;}catch{}}
    const prose=text=>answerHtml(text||'',result,runId);
    const refs=items=>(items||[]).map(c=>' ['+c.id+']').join('');
    const finding=item=>{
      const marker='来源上下文与适用条件：',parts=(item.text||'').split(marker);
      const label=({observation:'直接观察',hypothesis:'待验证原因',supported:'资料支持'})[item.certainty]||'判断';
      return `<div class="report-finding"><span class="tag">${label}</span>${prose(parts[0]+refs(item.citations))}${parts.length>1?`<details><summary>来源上下文与适用条件</summary>${prose(parts.slice(1).join(marker))}</details>`:''}</div>`;
    };
    const grouped=new Map();
    for(const action of answer.actions||[]){
      const key=action.text+'|'+action.prerequisites;
      if(!grouped.has(key))grouped.set(key,{...action,commands:[]});
      const group=grouped.get(key);if(action.command&&!group.commands.includes(action.command))group.commands.push(action.command);
    }
    const actions=[...grouped.values()].map((action,i)=>{
      const quote=[...new Set((action.citations||[]).map(c=>c.quote).filter(Boolean))].join('\n\n');
      const literal=(action.text||'').startsWith('手册中的操作说明（原文）：');
      return `<div class="report-action"><strong>建议 ${i+1}</strong>${prose((literal?quote:action.text)+refs(action.citations))}
        ${action.prerequisites?`<p class="report-condition">前提与风险：${esc(action.prerequisites)}</p>`:''}
        ${action.commands.map(command=>`<pre>${esc(command)}</pre>`).join('')}
        ${literal?`<details><summary>查看完整来源与适用条件</summary>${prose(action.text)}</details>`:''}</div>`;
    }).join('');
    return `<div class="incident-report"><section><h3>现场概况</h3><p>${esc(incident?.scenarioName||'本次现场')} · ${esc(service||'待确认服务')}</p><p class="sub">来源：${incident?.scenarioId==='user-materials'?'用户提交材料':'模拟现场'}</p></section>
      <section><h3>当前判断</h3>${(answer.findings||[]).map(finding).join('')||'<p class="muted">目前证据不足，不能确认原因。</p>'}</section>
      ${answer.contradictions?.length?`<section><h3>反证与限制</h3>${answer.contradictions.map(finding).join('')}</section>`:''}
      <section><h3>建议操作</h3>${actions||'<p class="muted">暂无通过来源核对的操作建议。</p>'}</section>
      <section><h3>待确认项</h3>${answer.missingEvidence?.length?`<ul>${answer.missingEvidence.map(m=>`<li>${esc(m)}</li>`).join('')}</ul>`:'<p class="muted">暂无补充项；候选原因仍需现场验证。</p>'}</section></div>`;
  }
  function citedIds(result){const ids=new Set((result.answer?.citations||[]).map(ref=>ref.id));for(const key of ['findings','actions','contradictions'])for(const item of result.answer?.[key]||[])for(const ref of item.citations||[])ids.add(ref.id);return ids;}
  function timingBox(run){return `<details class="run-timing" ${active(run.status)?'open':''}><summary data-timing-run="${esc(run.id)}">耗时明细 · 每次模型与工具调用</summary><div id="timing-${esc(run.id)}"><p class="sub">展开后加载调用耗时。</p></div></details>`;}
  function paintTiming(id){
    const target=document.getElementById('timing-'+id),run=knownRuns.get(id);if(!target||!run)return;
    const items=[...(timingEvents.get(id)||new Map()).values()],data=Platform.runTimings(items,run);
    const seconds=ms=>(Math.max(0,ms||0)/1000).toFixed(1)+' 秒';
    const labels={success:'完成',failed:'失败',cancelled:'已取消',timed_out:'已超时',interrupted:'已中断',running:'进行中'};
    const wall=run.result?.elapsedMs??Math.max(0,(Date.parse(run.finishedAt)||Date.now())-Date.parse(run.createdAt));
    target.innerHTML=`<p class="timing-totals">${active(run.status)?'已等待':'总历时'} <b>${seconds(wall)}</b> · LLM 累计 <b>${seconds(data.llmMs)}</b> · 工具累计 <b>${seconds(data.toolMs)}</b></p>`+
      (data.rows.length?`<div class="timing-table-wrap"><table class="timing-table"><thead><tr><th>调用步骤</th><th>耗时</th><th>状态</th></tr></thead><tbody>${data.rows.map((r,i)=>`<tr><td>${i+1}. ${esc(r.label)}${r.model?`<small>${esc(r.model)}</small>`:''}${r.direction?`<small>${esc(r.direction)}</small>`:''}${r.firstResponseMs!=null?`<small>上游首包 ${seconds(r.firstResponseMs)}</small>`:''}</td><td>${r.estimated?'约 ':''}${seconds(r.elapsedMs)}</td><td>${esc(({local_deadline:'本地时间上限',upstream_idle:'上游响应停滞',upstream_first_response_timeout:'等待上游首包超时'})[r.failureKind]||labels[r.outcome]||r.outcome)}${r.httpStatus&&r.httpStatus>=400?` · HTTP ${r.httpStatus}`:''}</td></tr>`).join('')}</tbody></table></div>`:'<p class="sub">等待调用开始…</p>')+
      '<p class="sub">检索子项已包含在工具耗时内；并行调用会重叠，各项累计不相加。首包表示收到上游数据，不等于答案已完成；旧记录可能没有首包信息。</p>';
  }
  async function loadTiming(id){
    let after=0,batch;const records=timingEvents.get(id)||new Map();timingEvents.set(id,records);
    do{batch=await api(base+'/runs/'+id+'/events?after='+after);for(const event of batch)records.set(event.sequence,event);after=batch.at(-1)?.sequence||after;}while(batch.length===200);
    paintTiming(id);
  }
  function watchTiming(run,source){
    if(timingTimer)clearInterval(timingTimer);
    const records=timingEvents.get(run.id)||new Map();timingEvents.set(run.id,records);
    for(const type of ['model_start','model_response','usage','tool_start','tool_end','tool_error','terminal'])source.addEventListener(type,event=>{
      try{const data=JSON.parse(event.data),sequence=+event.lastEventId;records.set(sequence,{sequence,type,data,createdAt:records.get(sequence)?.createdAt||data.startedAt||new Date().toISOString()});paintTiming(run.id);}catch(error){fail(error);}
    });
    loadTiming(run.id).catch(fail);
    timingTimer=setInterval(()=>{if(activeRun?.id===run.id)paintTiming(run.id);else{clearInterval(timingTimer);timingTimer=null;}},1000);
  }
  function renderRun(run){const result=run.result;return `<article class="message user"><div class="sub">你 · ${time(run.createdAt)}</div><div class="answer-text">${esc(run.input.question)}</div></article><article class="message" id="run-${esc(run.id)}"><div class="row"><strong>${esc(agent.config.name)}</strong>${status(run.status)}</div>${firstIncident(run)?`<details class="report-snapshot"><summary>查看原始现场</summary>${facts(firstIncident(run))}</details>`:''}${result?`${result.kind==='incident_report'?reportHtml(result,run.id):answerHtml(result.text,result,run.id)}${result.notices?.map(n=>`<p class="notice">${esc(n)}</p>`).join('')||''}${result.evidence.length?`<details><summary>${result.kind==='incident_report'?'证据来源与原始现场':'本轮检索与读取材料'} · ${result.evidence.length} 条，实际引用 ${citedIds(result).size} 条</summary><div>${result.evidence.map((e,i)=>details(`[${i+1}] ${e.sourceFile||e.id} · ${e.title}`,e.content)).join('')}<p class="sub">引用原文固定在本次版本，更新文档不会改变旧报告的证据。</p></div></details>`:''}<div class="sub">${esc(result.model)} · ${result.toolCalls} 次工具调用 · ${(result.elapsedMs/1000).toFixed(1)} 秒</div>`:run.error?`<p class="error-box">${esc(run.error)}</p>`:'<p class="muted">任务正在执行，过程与结果会自动更新。</p>'}${timingBox(run)}<details><summary data-history-trace="${esc(run.id)}">技术执行记录</summary><pre id="history-trace-${esc(run.id)}">展开后加载…</pre></details></article>`;}
  function revealRun(id){requestAnimationFrame(()=>document.getElementById('run-'+id)?.scrollIntoView({block:'start',behavior:'smooth'}));}
  async function submit(diagnose){if(readOnly()){toast('升级前会话仅供查看，请新建会话');return;}let question=$('#question').value.trim();if(diagnose&&!question)question='分析本次提交的材料，说明观察、待验证原因和有来源的检查建议。';if(!question){toast(diagnose?'请输入症状或补充信息':'请输入问题',true);return;}setBusy(true);try{if(debug&&!sessionId){const created=await api(base+'/sessions',{method:'POST',body:{agentId:agent.id,origin:'debug',agentVersion:debugVersion}});sessionId=created.id;}const body={agentId:agent.id,sessionId,question,diagnose,scenarioId:debug&&currentAgent().config.strategy==='workflow'?($('#scenario').value||null):null,incidentText:$('#incidentText').value.trim()};const run=await api(base+'/runs',{method:'POST',body});sessionId=run.sessionId;knownRuns.set(run.id,run);$('#question').value='';$('#incidentText').value='';if($('#messages .empty'))$('#messages').innerHTML='';$('#messages').insertAdjacentHTML('beforeend',renderRun(run));await listSessions();watch(run);revealRun(run.id);}catch(error){fail(error);setBusy(false);}}
  function watch(run){activeRun=run;setBusy(true);$('#liveTrace').hidden=false;$('#traceContent').textContent='';$('#currentPlan').innerHTML='';if(stream)stream.close();stream=new EventSource(base+'/runs/'+run.id+'/stream');watchTiming(run,stream);
    const log=(type,data)=>{const target=$('#traceContent');target.textContent+=`\n${type}\n${JSON.stringify(data,null,2)}\n`;};
    for(const type of ['routing','workflow_stage','workflow_worker_finished','plan_created','step_started','step_observed','step_finished','plan_revised','plan_stopped','stage','tool_start','tool_end','tool_error','cache_hit','retrieval','usage','plan','error','terminal'])stream.addEventListener(type,e=>{let data;try{data=JSON.parse(e.data);}catch{return;}log(type,data);if(type==='stage'||type==='workflow_stage'){$('#runStage').innerHTML=`<span class="progress-dot"></span>${esc(data.message)}`;}if(type==='tool_start')$('#runStage').innerHTML=`<span class="progress-dot"></span>${data.direction?esc(data.direction)+' · ':''}正在调用 ${esc((data.data||data).name)}`;if(type==='plan')renderPlan(data);});
    stream.addEventListener('answer_item',e=>{try{const data=JSON.parse(e.data),target=$('#run-'+run.id);let live=target.querySelector('.live-answer');if(!live){target.querySelector('p.muted')?.remove();live=document.createElement('div');live.className='live-answer';target.insertBefore(live,target.querySelector(':scope > details'));}live.insertAdjacentHTML('beforeend',`<p class="answer-text">${data.item.certainty==='hypothesis'?'待验证：':''}${esc(data.item.text)}</p>${data.item.prerequisites?`<p class="sub">前提与风险：${esc(data.item.prerequisites)}</p>`:''}${data.item.command?`<pre>${esc(data.item.command)}</pre>`:''}`);}catch(error){fail(error);}});
    for(const type of ['answer_delta','answer_general','answer_text'])stream.addEventListener(type,e=>{
      try{const data=JSON.parse(e.data),target=$('#run-'+run.id);let live=target.querySelector('.live-prose');
        if(!live){target.querySelector('p.muted')?.remove();live=document.createElement('div');live.className='live-prose answer-text';live.setAttribute('aria-live','polite');target.insertBefore(live,target.querySelector(':scope > details'));}
        live.dataset.text=type==='answer_delta'?(live.dataset.text||'')+(data.text||''):(data.text||'');
        live.innerHTML=answerHtml(live.dataset.text,{kind:'knowledge_answer',evidence:[]},run.id);
      }catch(error){fail(error);}
    });
    stream.addEventListener('done',async()=>{stream.close();stream=null;try{const completed=await api(base+'/runs/'+run.id);knownRuns.set(run.id,completed);const target=$('#run-'+run.id);const temp=document.createElement('div');temp.innerHTML=renderRun(completed);target.replaceWith(temp.lastElementChild);await loadTiming(run.id);document.getElementById('timing-'+run.id).parentElement.open=true;revealRun(run.id);$('#runStage').textContent='';activeRun=null;setBusy(false);await listSessions();}catch(error){fail(error);setBusy(false);}});
    stream.onerror=()=>{$('#runStage').textContent='连接暂时中断，正在恢复执行记录…';};
  }
  async function restoreLegacy(){
    let local=[];try{local=JSON.parse(localStorage.getItem('chatHistories')||'[]');}catch{}
    const ids=await api(base+'/admin/legacy-history');const records=new Map(ids.map(id=>[id,{id,title:id}]));
    if(Array.isArray(local))for(const item of local)if(item&&typeof item.id==='string'&&Array.isArray(item.messages))records.set(item.id,item);
    dialog('恢复升级前的历史',`<p class="sub">旧版未区分用户。管理员可将自己确认的记录恢复到当前账号；原文件及浏览器记录继续保留。</p><label>历史记录<select name="legacyId" required>${[...records.values()].map(r=>`<option value="${esc(r.id)}">${esc(r.title||r.id)}</option>`).join('')}</select></label>`,async form=>{const record=records.get(form.get('legacyId'));if(!record)throw new Error('没有可恢复记录');const restored=await api(base+'/admin/legacy-history',{method:'POST',body:{id:record.id,title:(record.title||record.id).slice(0,80),messages:(record.messages||[]).filter(m=>m&&typeof m.content==='string').map(m=>({role:m.role||m.type,content:m.content}))}});await selectAgent('oncall');await openSession(restored.sessionId);toast('历史已恢复，升级前会话仅供查看');});
  }
  $('#moreSessions').onclick=()=>listSessions(true).catch(fail);
  $('#restoreLegacy').onclick=()=>restoreLegacy().catch(fail);
  $('#composer').onsubmit=e=>{e.preventDefault();submit(false);};$('#diagnose').onclick=()=>submit(true);
  $('#question').onkeydown=e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();submit(false);}};
  $('#stop').onclick=async()=>{if(!activeRun)return;$('#stop').disabled=true;try{await api(base+'/runs/'+activeRun.id+'/cancel',{method:'POST'});}catch(error){fail(error);}finally{$('#stop').disabled=false;}};
  $('#newSession').onclick=()=>newSession().catch(fail);$('#agentSelect').onchange=()=>selectAgent($('#agentSelect').value).catch(fail);
  $('#scenario').onchange=async()=>{sceneInfo();if(sessionId){await newSession();toast('已为新的现场创建空会话，旧报告保留在历史中');}};
  $('#logout').onclick=async()=>{await api('/api/auth/logout',{method:'POST'});location.href='/login.html';};$('#account').onclick=()=>Platform.account(me);
  document.addEventListener('click',async e=>{
    const simulation=e.target.closest('[data-simulation]');if(simulation){
      if(activeRun||readOnly())return;
      const sample=simulation.dataset.simulation==='slo'
        ?{question:'请分析这份 SLO 错误率模拟告警，区分观察与假设，给出有来源的检查建议。',material:'[模拟材料，仅用于演示]\n服务: api-gateway\n告警: SLO error rate violation\n观测: 过去 10 分钟 HTTP 5xx 错误率 8%，阈值 1%。\n日志: request failed: upstream timeout\n尚未确认: 上游服务状态、近期变更、影响范围。'}
        :{question:'请分析这份 PostgreSQL 磁盘空间模拟告警，说明还需要确认什么，再给出有来源的建议。',material:'[模拟材料，仅用于演示]\n服务: PostgreSQL\n告警: PostgreSQL disk space\n观测: 数据盘使用率 96%，阈值 90%。\n日志: ERROR: could not extend file: No space left on device\n尚未确认: WAL 占用、数据增长来源、备份与保留策略。'};
      if(sessionId)await newSession();
      $('#scenario').value='';$('#question').value=sample.question;$('#incidentText').value=sample.material;$('#incidentInput').open=true;$('#question').focus();return;
    }
    const session=e.target.closest('[data-session]');if(session){if(activeRun){toast('请先停止当前任务再切换会话');return;}openSession(session.dataset.session).catch(fail);}
    const citation=e.target.closest('[data-citation]');if(citation){e.preventDefault();const evidence=knownRuns.get(citation.dataset.run)?.result?.evidence.find(x=>x.id===citation.dataset.citation);if(evidence)dialog('引用原文',`<p>${esc(evidence.sourceFile||evidence.id)}</p><p class="sub">版本 ${esc(evidence.version)} · 字符 ${evidence.start}–${evidence.end}</p><pre>${esc(evidence.content)}</pre>`);}
    const timing=e.target.closest('[data-timing-run]');if(timing)loadTiming(timing.dataset.timingRun).catch(fail);
    const trace=e.target.closest('[data-history-trace]');if(trace){try{let all=[],after=0,batch;do{batch=await api(base+'/runs/'+trace.dataset.historyTrace+'/events?after='+after);all.push(...batch);after=batch.at(-1)?.sequence||after;}while(batch.length===200);$('#history-trace-'+trace.dataset.historyTrace).textContent=JSON.stringify(all,null,2);}catch(error){fail(error);}}
    const downloadBtn=e.target.closest('[data-action="download"]');if(downloadBtn){e.preventDefault();await downloadAttachment(downloadBtn.dataset.id);}
    const deleteBtn=e.target.closest('[data-action="deleteAttachment"]');if(deleteBtn){e.preventDefault();await deleteAttachment(deleteBtn.dataset.id);}
  });
  $('#chatUpload').onchange=async e=>{const file=e.target.files[0];e.target.value='';if(!file)return;try{const data=await api(base+'/admin/catalog');const kbIds=new Set(agent.config.knowledgeBaseIds);const datasetIds=new Set(data.knowledgeBases.filter(k=>kbIds.has(k.id)).flatMap(k=>k.datasetIds));const datasets=data.datasets.filter(d=>datasetIds.has(d.id));if(!datasets.length)throw new Error('当前智能体未绑定可上传的数据集，请先在Console配置');dialog('上传到当前 Agent 的资料',`<p>${esc(file.name)}</p><label>数据集<select name="datasetId">${datasets.map(d=>`<option value="${esc(d.id)}">${esc(d.name)}</option>`).join('')}</select></label><label>目录（可选）<input name="folder" placeholder="例如 notes"></label><p class="sub">与 Console 共用上传规则；文档生效后新建会话使用新版本。</p>`,async f=>{const upload=new FormData();upload.append('file',file);upload.append('datasetId',f.get('datasetId'));upload.append('folder',f.get('folder'));const result=await api(base+'/admin/documents/upload',{method:'POST',body:upload});toast(result.action==='unchanged'?'文档已存在且索引正常':'文档已生效，新建会话即可使用');});}catch(error){fail(error);}};
  $('#uploadAttachmentBtn').onclick=()=>uploadAttachment().catch(fail);
  async function init(){me=await auth();$('#restoreLegacy').hidden=me.role!=='ADMIN';$('#consoleLink').hidden=me.role!=='ADMIN';agents=await api(base+'/agents');scenes=debug&&me.role==='ADMIN'?await api(base+'/scenarios'):[];$('#agentSelect').innerHTML=agents.map(a=>`<option value="${esc(a.id)}">${esc(a.config.name)}${a.enabled?'':'（已停用，仅历史）'}</option>`).join('');$('#scenario').innerHTML='<option value="">使用提交的材料</option>'+scenes.map(s=>`<option value="${esc(s.scenario_id)}">${esc(s.display_name||s.scenario_id)}</option>`).join('');await selectAgent(new URLSearchParams(location.search).get('agent'));}
  init().catch(fail);
})();

