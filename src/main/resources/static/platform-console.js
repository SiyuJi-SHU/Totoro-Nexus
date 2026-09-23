(() => {
  const {$,esc,api,auth,toast,fail,status,time,details,dialog}=Platform;
  const base='/api/platform/admin';let catalog,me,page='agents',datasetId='',folder='',evalJobs=[],evalWorkspaceId='',activeEvalReport=null,lastRecall=null,viewGeneration=0;
  const pages={agents:['智能体','定义任务、绑定知识和工具，用同一个工作台运行。'],knowledge:['知识库管理','直接管理知识库和文档；原文和索引版本一同生效，失败保留旧版本。'],tools:['工具中心','管理内置工具、MCP 连接和可复用工具集。'],recall:['召回测试','与 Agent 共用检索实现，分别观察粗排、精排与原文证据。'],evaluation:['评测','按知识库验证召回质量与关联 Agent 的任务表现。'],monitor:['运行监控','查看真实执行过程、业务结果、模型用量和失败阶段。']};
  const strategyName=value=>({react:'ReAct',workflow:'Workflow',plan_execute_replan:'Plan–Execute–Replan'})[value]||value;
  const options=(items,selected='',label='name')=>items.map(x=>`<option value="${esc(x.id)}" ${x.id===selected?'selected':''}>${esc(x[label]||x.config?.name||x.id)}</option>`).join('');
  const checks=(name,items,selected=[])=>`<div class="checks">${items.map(x=>`<label><input type="checkbox" name="${name}" value="${esc(x.id)}" ${selected.includes(x.id)?'checked':''}>${esc(x.name||x.title)}</label>`).join('')||'<span class="sub">暂无可绑定项</span>'}</div>`;
  const field=(label,name,value='',type='text',extra='')=>`<label>${label}<input name="${name}" type="${type}" value="${esc(value)}" ${extra}></label>`;
  const button=(action,id,label,css='')=>`<button class="${css}" data-action="${action}" data-id="${esc(id)}">${label}</button>`;
  const table=(headers,rows)=>`<div class="table-scroll"><table><thead><tr>${headers.map(x=>`<th>${esc(x)}</th>`).join('')}</tr></thead><tbody>${rows.join('')||`<tr><td colspan="${headers.length}"><div class="empty">暂无记录</div></td></tr>`}</tbody></table></div>`;
  async function load() {
    const generation=++viewGeneration,current=()=>generation===viewGeneration;
    page=pages[location.hash.slice(1)]?location.hash.slice(1):'agents';
    const requestedPage=page;
    $('#title').textContent=$('#breadcrumb').textContent=pages[page][0];$('#description').textContent=pages[page][1];
    document.querySelectorAll('.nav a').forEach(a=>a.classList.toggle('active',a.dataset.page===page));
    $('#page').innerHTML='<div class="empty">正在读取…</div>';
    $('#page').setAttribute('aria-busy','true');
    try{const loaded=await api(base+'/catalog');if(!current())return;catalog=loaded;await renderers[requestedPage](current);}catch(error){if(current())$('#page').innerHTML=`<div class="error-box">${esc(error.message)}</div>`;}
    finally{if(current()){$('#page').dataset.view=requestedPage;$('#page').setAttribute('aria-busy','false');}}
  }
  const renderers={
    async agents(){
      const enabled=catalog.agents.filter(a=>a.enabled).length;
      $('#page').innerHTML=`
        <section class="workspace-overview" aria-label="工作区概览">
          <div class="overview-copy"><span class="overview-kicker">WORKSPACE</span><strong>${enabled} 个智能体正在值守</strong><span>保存配置即发布新版本，用于新会话。</span></div>
          <div class="overview-metrics">
            <div><b>${catalog.knowledgeBases.length}</b><span>知识库</span></div>
            <div><b>${catalog.tools.length}</b><span>可用工具</span></div>
            <div><b>${catalog.agents.reduce((sum,a)=>sum+a.config.toolSetIds.length,0)}</b><span>工具集绑定</span></div>
          </div>
        </section>
        <div class="toolbar agent-toolbar"><span class="sub">${catalog.agents.length} 个智能体 · 新会话使用当前发布版本</span>${button('agent-new','','＋ 创建智能体','primary')}</div>
        <div class="grid agent-grid">${catalog.agents.map(a=>`
          <article class="card agent-card ${a.enabled?'':'is-disabled'}">
            <div class="agent-card-head">
              <div class="agent-avatar" aria-hidden="true"><i></i></div>
              <div class="agent-state"><span class="state-dot"></span>${a.enabled?'运行中':'已停用'}</div>
              <span class="agent-version">v${a.version}</span>
            </div>
            <div class="agent-card-body">
              <h2>${esc(a.config.name)}</h2>
              <p class="muted">${esc(a.config.description||'尚未填写智能体说明。')}</p>
              <div class="agent-meta">
                <span><b>${a.config.knowledgeBaseIds.length}</b> 知识库</span>
                <span><b>${a.config.toolSetIds.length}</b> 工具集</span>
                <span>${esc(strategyName(a.config.strategy))}</span>
              </div>
            </div>
            <div class="agent-actions">
              <a class="button primary" href="/?agent=${encodeURIComponent(a.id)}">打开对话 <span aria-hidden="true">↗</span></a>
              ${button('agent-edit',a.id,'⚙ 配置')}
              <details class="action-menu"><summary title="更多操作" aria-label="更多操作">•••</summary><div class="action-menu-popover">
                ${button('agent-versions',a.id,'版本与回退')}
                ${button('agent-toggle',a.id,a.enabled?'停用智能体':'启用智能体')}
                ${button('agent-history-delete',a.id,'删除对话记录','danger')}
                ${button('agent-delete',a.id,'删除智能体','danger')}
              </div></details>
            </div>
          </article>`).join('')}</div>`;
    },
    async knowledge(current){
      // 新版：知识库手风琴式展开，隐藏数据集概念
      let expandedKb = localStorage.getItem('expandedKb') || '';
      if(!catalog.knowledgeBases.some(kb=>kb.id===expandedKb))expandedKb='';

      // 获取所有知识库的文档数量
      const kbDocCounts = new Map();
      for(const kb of catalog.knowledgeBases) {
        try {
          const docs = await api(base+'/documents?knowledgeBaseId='+encodeURIComponent(kb.id));
          kbDocCounts.set(kb.id, docs.length);
        } catch(e) {
          kbDocCounts.set(kb.id, 0);
        }
      }

      const expandedDocs = expandedKb ? await api(base+'/documents?knowledgeBaseId='+encodeURIComponent(expandedKb)) : [];
      if(!current())return;

      $('#page').innerHTML=`
        <div class="toolbar">
          <span class="sub">${catalog.knowledgeBases.length} 个知识库</span>
          <div class="actions">
            ${button('kb-new','','＋ 新建知识库','primary')}
          </div>
        </div>

        ${catalog.knowledgeBases.map(kb=>{
          const isExpanded = kb.id === expandedKb;
          const kbDocs = isExpanded ? expandedDocs : [];
          const docCount = kbDocCounts.get(kb.id) || 0;
          return `
            <section class="panel kb-section" style="margin-bottom:16px">
              <div class="kb-header" data-kb-id="${esc(kb.id)}" style="cursor:pointer;display:flex;justify-content:space-between;align-items:center">
                <div>
                  <h3 style="margin:0;display:flex;align-items:center;gap:8px">
                    ${isExpanded ? '▼' : '▶'} ${esc(kb.name)}
                    <span class="tag">${docCount} 篇文档</span>
                  </h3>
                  <p class="sub" style="margin:5px 0 0 24px">${esc(kb.description)}</p>
                </div>
                <div class="actions">
                  ${button('kb-edit',kb.id,'⚙️ 配置')}
                  ${button('kb-delete',kb.id,'删除','danger')}
                </div>
              </div>

              ${isExpanded ? `
                <div class="kb-content" style="margin-top:16px;padding-top:16px;border-top:1px solid var(--line)">
                  <div class="toolbar kb-upload-toolbar">
                    <div class="kb-upload-actions">
                      <label class="button primary">
                        ↑ 上传文件
                        <input type="file" accept=".md,.txt,text/markdown,text/plain" class="kb-upload" data-kb-id="${esc(kb.id)}" hidden>
                      </label>
                      <label class="button">
                        ▣ 上传文件夹
                        <input type="file" accept=".md,.txt,text/markdown,text/plain" class="kb-folder-upload" data-kb-id="${esc(kb.id)}" webkitdirectory directory multiple hidden>
                      </label>
                    </div>
                    <span class="sub">支持 UTF-8 Markdown 和 TXT，单个文件最大 5MB</span>
                  </div>
                  <div class="kb-upload-status" data-kb-id="${esc(kb.id)}" role="status"></div>
                  ${kbDocs.length ? table(['文档','索引状态','分块/字符','更新时间','操作'],
                    kbDocs.map(d=>`<tr>
                      <td class="filename">${esc(d.path)}${d.error?`<div class="sub error">${esc(d.error)}</div>`:''}</td>
                      <td>${status(d.status)}</td>
                      <td>${d.chunkCount} / ${d.contentLength}</td>
                      <td class="subtle">${time(d.updatedAt)}</td>
                      <td><div class="actions">
                        ${button('doc-preview',d.id,'预览')}
                        ${button('doc-delete',d.id,'删除','danger')}
                      </div></td>
                    </tr>`)
                  ) : '<p class="muted" style="padding:20px;text-align:center">暂无文档，请上传</p>'}
                </div>
              ` : ''}
            </section>
          `;
        }).join('')}
      `;

      // 知识库展开/折叠
      document.querySelectorAll('.kb-header').forEach(header => {
        header.onclick = (e) => {
          if(e.target.closest('button')) return;
          const kbId = header.dataset.kbId;
          localStorage.setItem('expandedKb', kbId===expandedKb?'':kbId);
          load();
        };
      });

      // 单文件和文件夹共用同一条版本化入库链路。
      document.querySelectorAll('.kb-upload,.kb-folder-upload').forEach(input => {
        input.onchange = e => uploadKnowledgeFiles(input,e.target.files);
      });
    },
    async tools(){
      $('#page').innerHTML=`<div class="toolbar"><span class="sub">${catalog.tools.length} 个可用工具</span><div class="actions">${button('tool-debug','','调试工具')}</div></div><section class="panel"><h2>工具集</h2>${catalog.toolSets.map(t=>`<p><strong>${esc(t.name)}</strong> · ${t.toolIds.length} 个工具 ${t.id==='knowledge-tools'?'<span class="tag">默认共享</span>':button('toolset-edit',t.id,'编辑')}</p>`).join('')}<h2>工具目录</h2><p class="sub">内置工具均为只读：知识访问受 Agent 绑定范围限制，材料访问只针对当前会话；Workflow 会先读取固定现场。MCP 外部工具需单独授权。</p>${table(['名称','来源','用途','参数'],catalog.tools.map(t=>`<tr><td>${esc(t.title)}<div class="sub">${esc(t.id)}</div>${!t.readOnly?'<span class="tag warn">首版不自动执行</span>':''}</td><td>${esc(t.source)}</td><td>${esc(t.description)}</td><td>${button('tool-schema',t.id,'Schema')}</td></tr>`))}</section><details style="margin-top:20px"><summary>高级：MCP 外部工具连接</summary><section class="panel" style="margin-top:12px"><h3>MCP 连接</h3>${button('toolset-new','','配置外部工具授权')}<p class="sub">Streamable HTTP · 凭据引用服务端环境变量。首版自动调用限服务声明的只读工具。</p><div class="toolbar" style="margin-bottom:12px">${button('mcp-new','','＋ MCP 连接','primary')}</div>${table(['连接','地址','状态','工具数','操作'],catalog.mcp.map(c=>`<tr><td>${esc(c.name)}</td><td>${esc(c.endpoint)}</td><td>${status(c.status)}<div>${esc(c.error||'')}</div></td><td>${c.tools.length}</td><td><div class="actions">${button('mcp-discover',c.id,'发现工具')}${button('mcp-edit',c.id,'配置')}</div></td></tr>`))}</section></details>`;
    },
    async recall(){
      const available=catalog.knowledgeBases.filter(k=>k.datasetIds.length>0),first=available[0];
      $('#page').innerHTML=`<div class="split"><form id="recallForm" class="panel recall-form"><h2>召回测试</h2><p class="sub">输入一条真实问题，检查实际命中的Chunk与排序。</p><label>知识库<select name="knowledgeBaseId" required>${options(available,first?.id)}</select></label><label>用户问题<textarea name="query" rows="4" required maxlength="8000" placeholder="输入自然语言、报错或专有术语"></textarea></label><details class="advanced-settings"><summary>高级设置</summary><div class="form-grid"><label>检索模式<select name="mode"><option value="semantic">语义检索（向量）</option><option value="keyword">全文检索（BM25）</option><option value="hybrid">混合检索（RRF）</option></select></label><label class="inline-check"><input type="checkbox" name="rerank" checked> 启用精排</label></div><div class="form-grid">${field('粗排候选数','candidateTopK',20,'number','min="1" max="100"')}${field('精排返回数','topK',10,'number','min="1" max="10"')}</div></details><button class="primary full-button" type="submit">开始召回测试</button><p class="sub recall-note">问题与资料主要语言不一致且首轮置信度较低时，会自动合并一次受控翻译候选。</p></form><div id="recallResult"><div class="empty">提交问题后显示召回摘要；点击结果卡查看证据原文。</div></div></div>`;
      const form=$('#recallForm'),kb=form.elements.knowledgeBaseId,mode=form.elements.mode;
      const syncMode=()=>{mode.value=catalog.knowledgeBases.find(k=>k.id===kb.value)?.retrievalMode||'semantic';};syncMode();kb.onchange=syncMode;
      form.onsubmit=async e=>{e.preventDefault();e.submitter.disabled=true;$('#recallResult').innerHTML='<div class="empty">正在检索…</div>';try{const f=new FormData(e.target);const request={query:f.get('query'),knowledgeBaseIds:[f.get('knowledgeBaseId')],mode:f.get('mode'),candidateTopK:+f.get('candidateTopK'),topK:+f.get('topK'),rerank:f.has('rerank')};const result=await api(base+'/recall',{method:'POST',body:request});lastRecall={result,request,knowledgeBaseId:f.get('knowledgeBaseId')};$('#recallResult').innerHTML=recallResult(result);}catch(err){$('#recallResult').innerHTML=`<div class="error-box">${esc(err.message)}</div>`;}finally{e.submitter.disabled=false;}};
    },
    async evaluation(current){
      const [workspaces,sets,jobs]=await Promise.all([api(base+'/evaluation/workspaces'),api(base+'/evaluation/sets'),api(base+'/evaluation/jobs')]);if(!current())return;evalJobs=jobs;window.platformEvalSets=sets;
      const visible=workspaces.filter(w=>w.documentCount>0||w.agents.length>0),primary=visible.filter(w=>w.agents.length),secondary=visible.filter(w=>!w.agents.length);
      const direct=new URLSearchParams(location.search),directJob=jobs.find(j=>j.id===direct.get('evalJob'));
      if(directJob?.config?.agentId){const job=await api(base+'/evaluation/jobs/'+directJob.id);if(!current())return;const suite=sets.find(s=>s.id===job.setId),kbId=job.config.knowledgeBaseIds?.[0]||'',workspace=workspaces.find(w=>w.knowledgeBase.id===kbId);evalWorkspaceId=kbId;activeEvalReport={job,suite,kbId};$('#page').innerHTML=renderAgentCaseReport(job,suite,direct.get('evalCase')||'',workspace);return;}
      activeEvalReport=null;if(directJob&&!evalWorkspaceId)evalWorkspaceId=directJob.config.knowledgeBaseIds?.[0]||'';
      const selected=workspaces.find(w=>w.knowledgeBase.id===evalWorkspaceId);
      if(selected){$('#page').innerHTML=renderEvaluationWorkspace(selected,sets,jobs);if(directJob)setTimeout(()=>showEvalJob(directJob.id,direct.get('evalCase')||'').catch(fail),0);return;}
      evalWorkspaceId='';
      $('#page').innerHTML=`<div class="toolbar"><span class="sub">选择知识库，查看当前资料版本下的 RAG 与 Agent 质量。</span></div><div class="evaluation-kb-grid">${(primary.length?primary:visible).map(renderEvaluationCard).join('')||'<div class="empty">还没有可评测的知识库</div>'}</div>${primary.length&&secondary.length?`<details class="archive evaluation-unbound"><summary>未绑定 Agent 的知识库 · ${secondary.length}</summary><div class="evaluation-kb-grid">${secondary.map(renderEvaluationCard).join('')}</div></details>`:''}`;
    },
    async monitor(current){
      const monitor=await import('/monitor.js?v=1');
      if(current())await monitor.mount($('#page'),catalog,current);
    }
  };
  async function uploadKnowledgeFiles(input,files){
    const kbId=input.dataset.kbId;
    const statusDiv=document.querySelector(`.kb-upload-status[data-kb-id="${kbId}"]`);
    const selection=FolderUpload.classify(files);
    const summary=FolderUpload.createSummary(selection);
    const renderSummary=target=>{
      if(!target)return;
      const failures=summary.failures.length?`<details class="kb-upload-failures"><summary>查看 ${summary.failures.length} 个失败文件</summary><ul>${summary.failures.map(x=>`<li><code>${esc(x.name)}</code><span>${esc(x.reason)}</span></li>`).join('')}</ul></details>`:'';
      target.innerHTML=`<div class="${summary.failed?'notice':'kb-upload-success'}"><strong>${esc(FolderUpload.summaryText(summary))}</strong>${summary.total?`<span>新入库 ${summary.indexed} · 更新 ${summary.updated} · 修复 ${summary.repaired} · 未变化 ${summary.unchanged}</span>`:''}${failures}</div>`;
    };
    input.value='';
    if(!selection.accepted.length){
      renderSummary(statusDiv);
      if(!summary.failed&&summary.skipped)statusDiv.innerHTML=`<div class="notice">所选文件夹中没有支持的文件；已跳过 ${summary.skipped} 个文件。</div>`;
      return;
    }
    document.querySelectorAll(`.kb-upload[data-kb-id="${kbId}"],.kb-folder-upload[data-kb-id="${kbId}"]`).forEach(el=>el.disabled=true);
    for(let i=0;i<selection.accepted.length;i++){
      const item=selection.accepted[i];
      statusDiv.innerHTML=`<div class="kb-upload-progress"><span>正在解析并入库 ${i+1} / ${selection.accepted.length}</span><strong>${esc(item.path)}</strong><progress max="${selection.accepted.length}" value="${i}"></progress></div>`;
      const data=new FormData();data.append('file',item.file,item.name);data.append('knowledgeBaseId',kbId);if(item.folder)data.append('folder',item.folder);
      try{FolderUpload.record(summary,item,await api(base+'/documents/upload',{method:'POST',body:data}));}
      catch(error){FolderUpload.record(summary,item,null,error);}
    }
    toast(FolderUpload.summaryText(summary),summary.failed>0);
    await load();
    renderSummary(document.querySelector(`.kb-upload-status[data-kb-id="${kbId}"]`));
  }
  const percent=n=>n==null?'—':(n*100).toFixed(2)+'%';
  const metricLabels={candidateHitRate:'候选池命中',contextEvidenceCoverage:'原文窗口证据',legacyLabelCases:'旧版待核对标注',groundingUnreviewedCases:'引用语义未核对',groundingNotApplicableCases:'引用语义不适用',hitAt1:'Hit@1',hitAtK:'Hit@K',targetRecallAtK:'目标召回率',ungradedCases:'未判分',gradedCases:'已判分',blockedCases:'服务审核拦截',mrr:'MRR',requiredEvidenceCoverage:'Chunk内证据',taskChecksPassRate:'任务通过率',groundednessPassRate:'证据可信率',toolAccuracyPassRate:'工具正确率',answerCorrectnessPassRate:'答案正确率',errors:'执行异常',degradedCases:'降级案例',p95LatencyMs:'P95耗时'};
  const countMetrics=new Set(['groundingUnreviewedCases','groundingNotApplicableCases','legacyLabelCases','errors','degradedCases','ungradedCases','gradedCases','blockedCases']);
  const metricValue=(key,value)=>value==null?'未标注':key==='p95LatencyMs'?((value/1000).toFixed(1)+' s'):countMetrics.has(key)?value:percent(value);
  const metricSummary=s=>!s?'':Object.entries(s).filter(([k])=>k in metricLabels).map(([k,v])=>`${metricLabels[k]}: ${metricValue(k,v)}`).join('<br>');
  const metricCards=(s,kind='retrieval')=>{if(!s)return '';const keys=kind==='agent'?['taskChecksPassRate','answerCorrectnessPassRate','groundednessPassRate','toolAccuracyPassRate','p95LatencyMs']:['hitAt1','hitAtK','mrr','requiredEvidenceCoverage','candidateHitRate','contextEvidenceCoverage'];return `<div class="metric-strip">${keys.filter(k=>k in s).map(k=>`<div><span>${esc(metricLabels[k])}</span><strong>${esc(metricValue(k,s[k]))}</strong></div>`).join('')}</div>`;};
  const agentResultScope=s=>{const total=Number(s?.totalCases||0),blocked=Number(s?.blockedCases||0),evaluated=Math.max(0,total-Number(s?.errors||0)-Number(s?.ungradedCases||0)-blocked);return `<p class="eval-result-scope"><b>${total} Case</b><span>${evaluated} 已评</span>${blocked?`<span>${blocked} 服务审核拦截</span>`:''}</p>`;};
  const evalStateMeta=state=>({unconfigured:['未建立','neutral'],unevaluated:['未评测','neutral'],running:['运行中','running'],current:['配置一致','current'],attention:['部分完成','warn'],stale:['已过期','stale'],cancelled:['已取消','neutral'],failed:['失败','error']})[state]||[state,'neutral'];
  const evalReason=track=>{if(track?.state==='attention'){const s=track.latestJob?.result?.summary||{},errors=Number(s.errors||0),total=Number(s.totalCases||0);return errors&&total?`${errors}/${total} 个 Case 未完成`:'部分 Case 未完成';}return track?.stateReason||'';};
  function evalState(track){const [label,css]=evalStateMeta(track.state);return `<span class="evaluation-state ${css}"><i></i>${esc(label)}</span>`;}
  function evalHeadline(track,kind){
    const summary=track.latestJob?.result?.summary;if(!summary||!['current','attention','stale'].includes(track.state))return `<span class="sub">${esc(track.stateReason)}</span>`;
    if(kind==='retrieval')return `<span><b>${percent(summary.hitAt1)}</b> Hit@1 · <b>${percent(summary.hitAtK)}</b> Hit@${summary.topK||3} · <b>${Number(summary.mrr??0).toFixed(2)}</b> MRR</span>`;
    return `<span><b>${percent(summary.taskChecksPassRate)}</b> 任务通过 · <b>${percent(('groundednessPassRate' in summary?summary.groundednessPassRate:summary.requiredEvidenceCoverage))}</b> 证据可信</span>`;
  }
  function renderEvaluationCard(w){
    const kb=w.knowledgeBase;
    return `<article class="card evaluation-kb-card"><div class="evaluation-kb-head"><div><span class="evaluation-kicker">KNOWLEDGE BASE</span><h2>${esc(kb.name)}</h2></div>${evalState(w.retrieval)}</div><p class="muted">${esc(kb.description||'尚未填写知识库说明。')}</p><div class="evaluation-snapshot"><span><b>${w.documentCount}</b> 篇文档</span><span><b>${w.chunkCount}</b> 个 Chunk</span><span>当前索引版本</span></div><div class="evaluation-card-lines"><div><span>RAG 召回</span>${evalHeadline(w.retrieval,'retrieval')}</div>${w.agents.map(a=>`<div><span>${esc(a.name)} v${a.version}</span>${evalState(a)}</div>`).join('')||'<div><span>关联 Agent</span><span class="sub">暂无</span></div>'}</div><button class="primary evaluation-enter" data-action="eval-workspace" data-id="${esc(kb.id)}">进入评测</button></article>`;
  }
  function evaluationRunRow(j,sets){
    const suite=sets.find(s=>s.id===j.setId),target=j.config.agentId?(catalog.agents.find(a=>a.id===j.config.agentId)?.config.name||j.config.agentId)+' v'+j.config.agentVersion:`RAG ${j.config.mode} · ${j.config.candidateTopK} → ${j.config.topK}`;
    const s=j.result?.summary,core=s?(j.config.agentId?`任务 ${percent(s.taskChecksPassRate)} · 证据 ${percent(('groundednessPassRate' in s?s.groundednessPassRate:s.requiredEvidenceCoverage))} · 工具 ${percent(s.toolAccuracyPassRate)}`:`Hit@1 ${percent(s.hitAt1)} · Hit@${s.topK||3} ${percent(s.hitAtK)} · MRR ${Number(s.mrr??0).toFixed(2)}`):((j.result?.completedCases??0)+' 个 Case 完成');
    return `<tr><td>${time(j.createdAt)}</td><td><strong>${esc(target)}</strong><div class="sub">${esc(suite?.name||j.setId)}</div></td><td>${status(j.status)}</td><td>${esc(core)}</td><td><div class="actions">${button('eval-job',j.id,'查看')}${j.status==='partial'&&j.config.agentId?button('eval-retry',j.id,'重试异常'):''}${['queued','running'].includes(j.status)?button('eval-cancel',j.id,'停止','danger'):''}</div></td></tr>`;
  }
  function renderEvaluationWorkspace(w,sets,jobs){
    const kb=w.knowledgeBase,activeSetIds=new Set([w.retrieval.setId,...w.agents.map(a=>a.setId)].filter(Boolean));
    const sourceKey=versions=>(versions||[]).map(v=>v.documentId+':'+v.version).sort().join('|'),currentSources=sourceKey(w.sourceVersions);
    const stableJson=value=>Array.isArray(value)?`[${value.map(stableJson).join(',')}]`:value&&typeof value==='object'?`{${Object.keys(value).sort().map(key=>JSON.stringify(key)+':'+stableJson(value[key])).join(',')}}`:JSON.stringify(value);
    const relevantJobs=jobs.filter(j=>{
      if(!activeSetIds.has(j.setId))return false;const suite=sets.find(s=>s.id===j.setId);if(!suite)return false;
      if(j.config.agentId){const agent=w.agents.find(a=>a.id===j.config.agentId);if(!agent||agent.version!==j.config.agentVersion)return false;}
      else if(j.setId!==w.retrieval.setId||j.config.knowledgeBaseIds?.length!==1||j.config.knowledgeBaseIds[0]!==kb.id)return false;
      if(['queued','running'].includes(j.status))return true;
      const manifestCases=j.result?.caseManifest?.cases||j.result?.caseManifest;
      return j.result?.pipelineVersion==='rag-v2'&&sourceKey(j.result?.sourceVersions)===currentSources&&stableJson(manifestCases)===stableJson(suite.cases);
    });evalJobs=relevantJobs;
    const retrievalSummary=w.retrieval.latestJob?.result?.summary;
    const retrievalMetrics=retrievalSummary?`<div class="evaluation-metrics"><div><span>Hit@1</span><strong>${percent(retrievalSummary.hitAt1)}</strong></div><div><span>Hit@${retrievalSummary.topK||3}</span><strong>${percent(retrievalSummary.hitAtK)}</strong></div><div><span>MRR</span><strong>${Number(retrievalSummary.mrr??0).toFixed(2)}</strong></div><div><span>Chunk 证据覆盖</span><strong>${percent(retrievalSummary.requiredEvidenceCoverage)}</strong></div><div><span>粗排候选命中</span><strong>${percent(retrievalSummary.candidateHitRate)}</strong></div><div><span>扩展原文证据覆盖</span><strong>${percent(retrievalSummary.contextEvidenceCoverage)}</strong></div></div>`:'<p class="evaluation-empty-copy">生成并确认少量 Chunk 级 Case 后，即可得到首个召回基线。</p>';
    const retrievalActions=w.retrieval.setId?`${button('eval-set',w.retrieval.setId,`查看 ${w.retrieval.caseCount} 个 Case`)}<button class="primary" data-action="eval-start" data-id="${esc(w.retrieval.setId)}" data-kb="${esc(kb.id)}">运行召回评测</button>`:`<button class="primary" data-action="eval-generate" data-id="${esc(kb.id)}">生成召回 Case</button>`;
    const agentRows=w.agents.map(a=>{const s=a.latestJob?.result?.summary,blocked=Number(s?.blockedCases||0),evaluated=s?Math.max(0,Number(s.totalCases||a.caseCount)-Number(s.errors||0)-Number(s.ungradedCases||0)-blocked):0,scope=blocked?`${s.totalCases||a.caseCount} Case · ${evaluated} 已评 · ${blocked} 审核拦截`:`${s?.totalCases||a.caseCount} Case · ${evaluated} 已评`;const metrics=s?`<div class="agent-eval-metrics"><span class="sub">${scope}</span><span><b>${percent(s.taskChecksPassRate)}</b> 任务通过</span><span><b>${percent(s.answerCorrectnessPassRate)}</b> 答案正确</span><span><b>${percent(('groundednessPassRate' in s?s.groundednessPassRate:s.requiredEvidenceCoverage))}</b> 证据可信</span><span><b>${percent(s.toolAccuracyPassRate)}</b> 工具正确</span><span><b>${s.p95LatencyMs==null?'—':(s.p95LatencyMs/1000).toFixed(1)+' s'}</b> P95</span></div>`:`<span class="sub">${esc(a.stateReason)}</span>`;const result=s&&a.latestJob?.id?`<button type="button" class="agent-eval-report" data-action="eval-report" data-id="${esc(a.latestJob.id)}" title="查看逐题评测报告"><div class="agent-eval-report-copy">${evalState(a)}${metrics}</div><b aria-hidden="true">›</b></button>`:`<div>${evalState(a)}${metrics}</div>`;const buildLabel=a.strategy==='workflow'?'建立 12 个事故 Case':'从召回 Case 建立';const actions=a.setId?`${button('eval-set',a.setId,`管理 ${a.caseCount} 个 Case`)}<button class="primary" data-action="eval-start" data-id="${esc(a.setId)}" data-agent="${esc(a.id)}" data-kb="${esc(kb.id)}">运行 Agent 评测</button>`:`<button class="primary" data-action="eval-agent-build" data-id="${esc(kb.id)}" data-agent="${esc(a.id)}" data-strategy="${esc(a.strategy)}">${buildLabel}</button><button data-action="eval-import" data-id="${esc(kb.id)}" data-agent="${esc(a.id)}">导入</button>`;return `<div class="agent-eval-row"><div class="agent-eval-name"><strong>${esc(a.name)} <small>v${a.version}</small></strong><span class="tag">${esc(strategyName(a.strategy))}</span></div>${result}<div class="actions">${actions}</div></div>`;}).join('')||'<div class="empty">没有绑定此知识库的 Agent</div>';
    return `<div class="evaluation-detail-head"><button data-action="eval-back" aria-label="返回知识库列表">←</button><div><span class="evaluation-kicker">EVALUATION WORKSPACE</span><h2>${esc(kb.name)}</h2><p class="sub">${w.documentCount} 篇文档 · ${w.chunkCount} 个 Chunk · 当前索引快照</p></div></div>
      <section class="panel evaluation-section"><div class="section-heading"><div><h2>RAG 召回质量</h2><p class="sub">只回答一个问题：正确 Chunk 能不能被稳定找回来。</p></div><div class="evaluation-title-state">${evalState(w.retrieval)}<span class="sub">${esc(evalReason(w.retrieval))}</span></div></div>${retrievalMetrics}<div class="actions evaluation-section-actions">${retrievalActions}</div></section>
      <section class="panel evaluation-section"><div class="section-heading"><div><h2>Agent 回答质量</h2><p class="sub">ReAct / Plan 共用已确认的知识问题；Workflow 使用冻结告警与日志。Case 跟策略走，分数跟 Agent 版本走。</p></div></div><div class="agent-eval-list">${agentRows}</div></section>
      <section class="panel evaluation-section"><div class="section-heading"><div><h2>运行记录</h2><p class="sub">配置一致表示资料、题目、模型和配置匹配；成绩对应记录中的评测时间，不表示当前部署已重新评测。</p></div><div class="actions">${relevantJobs.length>1?button('eval-compare','','对比结果'):''}</div></div>${relevantJobs.length?table(['时间','对象','状态','核心指标','操作'],relevantJobs.map(j=>evaluationRunRow(j,sets))):'<div class="empty evaluation-run-empty">暂无当前版本的运行记录</div>'}</section>`;
  }
  const evalReportUrl=(jobId,caseId='')=>`/console.html?evalJob=${encodeURIComponent(jobId)}${caseId?`&evalCase=${encodeURIComponent(caseId)}`:''}#evaluation`;
  function openEvalReport(jobId,caseId=''){history.pushState(null,'',evalReportUrl(jobId,caseId));return load();}
  function selectEvalReportCase(jobId,caseId){if(!activeEvalReport||activeEvalReport.job.id!==jobId)return openEvalReport(jobId,caseId);history.replaceState(null,'',evalReportUrl(jobId,caseId));$('#page').innerHTML=renderAgentCaseReport(activeEvalReport.job,activeEvalReport.suite,caseId);}
  function leaveEvalReport(kbId){const url=new URL(location.href);url.searchParams.delete('evalJob');url.searchParams.delete('evalCase');url.hash='evaluation';history.pushState(null,'',url);activeEvalReport=null;evalWorkspaceId=kbId||evalWorkspaceId;return load();}
  function reportCaseState(row={}){const blocked=String(row.error||'').includes('DataInspectionFailed')||(row.turns||[]).some(t=>String(t.reviewError||'').includes('DataInspectionFailed'));if(blocked)return {key:'blocked',label:'审核拦截'};if(row.error||row.status==='error')return {key:'error',label:'执行失败'};if(row.gradeStatus==='ungraded'||!row.gradeStatus)return {key:'ungraded',label:'未评分'};return row.gradeStatus==='passed'?{key:'passed',label:'通过'}:{key:'failed',label:'未通过'};}
  function reportManifestCases(job,suite){const manifest=job.result?.caseManifest;return (Array.isArray(manifest)?manifest:manifest?.cases)||suite?.cases||[];}
  function reportCases(job,suite){const results=job.result?.results||[],byId=new Map(results.map(r=>[r.id,r])),manifest=reportManifestCases(job,suite),known=new Set(manifest.map(c=>c.id));return [...manifest.map(c=>({id:c.id,question:c.question,gradeStatus:'ungraded',...byId.get(c.id)})),...results.filter(r=>!known.has(r.id))];}
  function reportTurnPairs(row,label){const turns=row.turns?.length?row.turns:[null],labels=label?.turns?.length?label.turns:[label];return turns.map((turn,index)=>({turn,label:labels.find(x=>x?.id===turn?.id)||labels[index]||label}));}
  function aggregateReview(pairs,keys){const checks=pairs.flatMap(({turn})=>keys.map(key=>turn?.modelReview?.checks?.[key]).filter(Boolean)),applicable=checks.filter(c=>c.status!=='not_applicable');if(!applicable.length)return {status:'not_applicable',reason:'此 Case 不适用该项检查'};const failed=applicable.filter(c=>c.status==='failed');return failed.length?{status:'failed',reason:failed.map(c=>c.reason).filter(Boolean).join('；')}:{status:'passed',reason:applicable.map(c=>c.reason).filter(Boolean).join('；')};}
  function reportDimensions(row,pairs,state){if(['blocked','error','ungraded'].includes(state.key)){const reason=row.error||'本次没有取得可评审的 Agent 回答';return ['任务结果','答案正确','证据可信','工具使用'].map((label,index)=>({label,english:['Task Success','Answer Correctness','Faithfulness','Tool Accuracy'][index],status:'ungraded',reason}));}
    const answer=aggregateReview(pairs,['answerCorrectness']),faith=aggregateReview(pairs,['citationSupport','actionSupport']),toolPairs=pairs.filter(({label})=>label?.requiredTools?.length||label?.toolArguments&&Object.keys(label.toolArguments).length),tool=toolPairs.length?(toolPairs.every(({turn})=>turn?.checks?.successfulTools&&turn?.checks?.toolArguments)?{status:'passed',reason:'必需工具已成功调用，参数约束通过'}:{status:'failed',reason:'必需工具未成功调用，或调用参数不符合 Case 约束'}):{status:'not_applicable',reason:'此 Case 未要求特定工具'};
    return [{label:'任务结果',english:'Task Success',status:state.key==='passed'?'passed':'failed',reason:state.key==='passed'?'固定规则与评审均通过':'至少一项固定规则或评审未通过'},{label:'答案正确',english:'Answer Correctness',...answer},{label:'证据可信',english:'Faithfulness',...faith},{label:'工具使用',english:'Tool Accuracy',...tool}];
  }
  const reportDimensionMeta=status=>({passed:['通过','passed'],failed:['未通过','failed'],ungraded:['未评分','ungraded'],not_applicable:['不适用','neutral']})[status]||[status,'neutral'];
  function reportReference(label){if(label?.referenceAnswer)return label.referenceAnswer;const lines=[];if(label?.expectedStatus)lines.push(`预期状态：${label.expectedStatus}`);if(label?.expectedKind)lines.push(`预期回答类型：${label.expectedKind}`);if(label?.requiredTools?.length)lines.push(`必须使用工具：${label.requiredTools.join('、')}`);return lines.join('\n')||'此 Case 没有配置文字参考答案，按固定规则验收。';}
  function reportSnapshotList(raw,key){try{const value=typeof raw==='string'?JSON.parse(raw):raw;return Array.isArray(value)?value:Array.isArray(value?.[key])?value[key]:[];}catch{return [];}}
  function renderReportIncidentSnapshot(label){const snapshot=label?.incidentSnapshot;if(!snapshot)return '';const alerts=reportSnapshotList(snapshot.alertsJson,'alerts'),logs=reportSnapshotList(snapshot.logsJson,'logs').sort((a,b)=>String(a.timestamp||a.observed_at||'').localeCompare(String(b.timestamp||b.observed_at||''))),alertRows=alerts.map(alert=>`<article class="eval-incident-alert"><div><strong>${esc(alert.alert_name||alert.description||'现场告警')}</strong>${alert.severity?`<span class="tag error">${esc(alert.severity)}</span>`:''}</div><p>${esc(alert.impact||alert.description||alert.current_value||'未记录告警摘要')}</p><dl><dt>服务 / 实例</dt><dd>${esc([alert.service,alert.instance].filter(Boolean).join(' · ')||'—')}</dd><dt>当前值</dt><dd>${esc(alert.current_value||'—')}</dd><dt>触发条件</dt><dd>${esc(alert.threshold||'—')}</dd></dl></article>`).join(''),logRows=logs.map(log=>`<li><time>${esc(log.timestamp||log.observed_at||'—')}</time><div><span class="eval-log-level ${String(log.level||'').toLowerCase()}">${esc(log.level||'LOG')}</span><strong>${esc([log.service,log.instance].filter(Boolean).join(' · ')||'现场日志')}</strong><p>${esc(log.message||JSON.stringify(log))}</p></div></li>`).join('');return `<section class="eval-report-incident"><div class="eval-incident-head"><div><span>评测现场</span><h2>${esc(label.displayName||snapshot.scenarioName||'冻结事故场景')}</h2></div><span class="tag">评测时冻结快照</span></div><div class="eval-incident-meta"><span><b>${alerts.length}</b> 条告警</span><span><b>${logs.length}</b> 条日志</span><span>冻结于 ${esc(time(snapshot.capturedAt))}</span></div><div class="eval-incident-grid"><div><h3>告警摘要</h3>${alertRows||'<p class="muted">快照中没有告警记录。</p>'}</div><div><h3>日志时间线</h3><ol class="eval-incident-logs">${logRows||'<li class="muted">快照中没有日志记录。</li>'}</ol></div></div><details class="eval-incident-raw"><summary>查看完整现场数据</summary><div class="eval-incident-raw-grid"><div><strong>告警 JSON</strong><pre>${esc(snapshot.alertsJson||'{}')}</pre></div><div><strong>日志 JSON</strong><pre>${esc(snapshot.logsJson||'{}')}</pre></div></div></details></section>`;}
  function renderReportComparisons(pairs,state){return pairs.map(({turn,label},index)=>`<section class="eval-report-turn">${pairs.length>1?`<h3>步骤 ${index+1} · ${esc(turn?.question||label?.question||'')}</h3>`:''}<div class="eval-answer-grid"><div class="eval-answer-pane"><span>参考答案 / 验收标准</span><div>${esc(reportReference(label))}</div></div><div class="eval-answer-pane agent-answer"><span>Agent 实际回答</span><div>${turn?.answer?esc(turn.answer):`<span class="muted">${esc(state.key==='blocked'?'模型服务审核拦截，本次没有生成回答。':'本次没有取得 Agent 回答。')}</span>`}</div></div></div></section>`).join('');}
  function renderReportEvidence(pairs){const evidence=[...new Map(pairs.flatMap(({turn})=>turn?.evidence||[]).map(item=>[item.id||`${item.sourceFile}:${item.start}`,item])).values()];return `<details class="eval-report-disclosure"><summary><span>引用与原文</span><b>${evidence.length} 条</b></summary><div class="eval-evidence-list">${evidence.map(item=>`<section><strong>${esc(item.title||item.sourceFile||item.id)}</strong><p class="sub">${esc(item.sourceFile||'')} · 字符 ${item.start??'—'}–${item.end??'—'}</p><div class="eval-evidence-copy">${esc(item.content||'')}</div></section>`).join('')||'<p class="muted">本次回答没有记录引用证据。</p>'}</div></details>`;}
  function renderReportTools(pairs){const rows=pairs.flatMap(({turn})=>Object.entries(turn?.toolArguments||{}).map(([tool,args])=>({tool,args}))),tools=[...new Set(pairs.flatMap(({turn})=>turn?.toolIds||[]))];return `<details class="eval-report-disclosure"><summary><span>工具执行过程</span><b>${tools.length} 个工具</b></summary><div>${rows.length?table(['工具','调用参数'],rows.map(row=>`<tr><td>${esc(row.tool)}</td><td><pre>${esc(JSON.stringify(row.args,null,2))}</pre></td></tr>`)):'<p class="muted">本次 Case 没有工具调用参数记录。</p>'}</div></details>`;}
  function renderReportUsage(job,pairs){const usage=pairs.flatMap(({turn})=>turn?.usage||[]);return `<details class="eval-report-disclosure"><summary><span>模型与用量</span><b>${usage.reduce((sum,row)=>sum+Number(row.total_tokens??row.totalTokens??0),0).toLocaleString()} Token</b></summary><div>${usage.length?table(['阶段','模型','输入','输出','总计'],usage.map(row=>`<tr><td>${esc(row.purpose||'—')}</td><td>${esc(row.model||job.result?.model||'—')}</td><td>${esc(row.input_tokens??row.inputTokens??'—')}</td><td>${esc(row.output_tokens??row.outputTokens??'—')}</td><td>${esc(row.total_tokens??row.totalTokens??'—')}</td></tr>`)):'<p class="muted">本次 Case 没有模型用量记录。</p>'}</div></details>`;}
  function renderAgentCaseReport(job,suite,caseId='',workspace=null){const rows=reportCases(job,suite),labels=new Map(reportManifestCases(job,suite).map(c=>[c.id,c])),selected=rows.find(r=>r.id===caseId)||rows[0];if(!selected)return '<div class="empty">这次评测没有 Case 结果</div>';const index=rows.indexOf(selected),label=labels.get(selected.id)||{},state=reportCaseState(selected),pairs=reportTurnPairs(selected,label),dimensions=reportDimensions(selected,pairs,state),failed=dimensions.filter(d=>d.status==='failed'),usage=pairs.flatMap(({turn})=>turn?.usage||[]),tokens=usage.reduce((sum,row)=>sum+Number(row.total_tokens??row.totalTokens??0),0),tools=[...new Set(pairs.flatMap(({turn})=>turn?.toolIds||[]))],agent=catalog.agents.find(a=>a.id===job.config.agentId),name=agent?.config?.name||job.config.agentId,model=job.result?.model||usage.find(row=>row.model)?.model||'—',kbId=job.config.knowledgeBaseIds?.[0]||activeEvalReport?.kbId||'',prev=rows[index-1],next=rows[index+1],directUrl=evalReportUrl(job.id,selected.id);activeEvalReport={job,suite,kbId};
    return `<div class="eval-report-page"><header class="eval-report-head"><button type="button" data-action="eval-report-back" data-kb="${esc(kbId)}" aria-label="返回 Agent 回答质量">←</button><div><span class="evaluation-kicker">AGENT CASE REPORT</span><h2>${esc(name)} <small>v${esc(job.config.agentVersion)}</small></h2><p class="sub">${esc(suite?.name||'Agent 评测')} · ${rows.length} Case</p></div><nav aria-label="Case 翻页"><button type="button" data-action="eval-report-case" data-id="${esc(job.id)}" data-case="${esc(prev?.id||'')}" ${prev?'':'disabled'} aria-label="上一题">←</button><strong>${String(index+1).padStart(2,'0')} / ${rows.length}</strong><button type="button" data-action="eval-report-case" data-id="${esc(job.id)}" data-case="${esc(next?.id||'')}" ${next?'':'disabled'} aria-label="下一题">→</button></nav></header><div class="eval-report-layout"><aside class="eval-report-rail"><div class="eval-report-rail-head"><strong>Case 列表</strong><span>${rows.length}</span></div><div class="eval-report-case-list">${rows.map((row,i)=>{const meta=reportCaseState(row);return `<button type="button" class="${row.id===selected.id?'active':''}" data-action="eval-report-case" data-id="${esc(job.id)}" data-case="${esc(row.id)}"><span>${String(i+1).padStart(2,'0')}</span><span><strong>${esc(row.question||labels.get(row.id)?.question||row.id)}</strong><small class="${meta.key}">${esc(meta.label)}</small></span></button>`;}).join('')}</div></aside><article class="eval-report-case"><div class="eval-report-case-head"><div><span class="tag ${['failed','error','blocked'].includes(state.key)?'error':state.key==='ungraded'?'warn':''}">${esc(state.label)}</span><span class="sub">Case ${String(index+1).padStart(2,'0')}</span></div><a class="button" href="${directUrl}">固定链接</a></div><section class="eval-report-question"><span>实际问题</span><h2>${esc(selected.question||label.question)}</h2></section>${renderReportIncidentSnapshot(label)}${selected.error?`<p class="error-box">${esc(selected.error)}</p>`:''}${renderReportComparisons(pairs,state)}<section class="eval-report-assessment"><div class="section-heading"><div><h2>Case 评测</h2><p class="sub">固定规则与模型评审共同判断，不使用人为加权总分。</p></div></div><div class="eval-case-dimensions">${dimensions.map(item=>{const [statusLabel,css]=reportDimensionMeta(item.status);return `<div><span>${esc(item.english)}</span><strong>${esc(item.label)}</strong><b class="${css}">${esc(statusLabel)}</b></div>`;}).join('')}</div>${failed.length?`<div class="eval-case-findings"><strong>未通过原因</strong>${failed.filter(item=>item.english!=='Task Success'||failed.length===1).map(item=>`<p><b>${esc(item.label)}：</b>${esc(item.reason)}</p>`).join('')}</div>`:''}<div class="eval-case-runtime"><span><b>${((selected.elapsedMs||0)/1000).toFixed(1)} s</b> 实际耗时</span><span><b>${tokens.toLocaleString()}</b> Token</span><span><b>${esc(model)}</b> 模型</span><span><b>${tools.length}</b> 工具</span></div></section><section class="eval-report-more">${renderReportEvidence(pairs)}${renderReportTools(pairs)}${renderReportUsage(job,pairs)}<details class="eval-report-disclosure"><summary><span>完整评审依据</span><b>${dimensions.length} 项</b></summary><div class="eval-review-list">${dimensions.map(item=>`<p><strong>${esc(item.label)}</strong><span>${esc(item.reason)}</span></p>`).join('')}</div></details></section></article></div></div>`;
  }
  function evaluationCoverage(s){const cases=s.cases||[],chunk=cases.filter(c=>c.expectedChunkIds?.length).length,answers=cases.filter(c=>c.referenceAnswer).length,scenes=cases.filter(c=>c.scenarioId).length;if(s.kind==='retrieval')return chunk?`${chunk}/${cases.length} 已标注Chunk`:'旧来源级标注';return scenes?`${scenes}/${cases.length} 场景已绑定`:answers?`${answers}/${cases.length} 有参考答案`:'规则标注';}
  function recallCard(d,rank,rankChange=''){
    return `<details class="recall-hit"><summary><span class="rank-number">#${rank}</span><span class="hit-identity"><strong>${esc(d.title||d.sourceFile)}</strong><small>${esc(d.sourceFile)}</small></span><span class="hit-score">${d.rerankScore!=null?'精排 '+d.rerankScore.toFixed(4):d.vectorDistance!=null?'距离 '+d.vectorDistance.toFixed(4):'BM25 '+(d.keywordScore?.toFixed(3)??'—')}</span>${rankChange?`<span class="tag">${esc(rankChange)}</span>`:''}</summary><div class="hit-detail"><div class="score"><span>Chunk ${esc(d.id)}</span><span>向量距离 ${d.vectorDistance?.toFixed(4)??'—'}</span><span>BM25 ${d.keywordScore?.toFixed(3)??'—'}</span>${d.rerankScore!=null?`<span>精排 ${d.rerankScore.toFixed(4)}</span>`:''}</div><div class="excerpt">${esc(d.content)}</div><div class="sub">版本 ${esc(d.version)} · 字符 ${d.start}–${d.end}</div></div></details>`;
  }
  function recallResult(r){
    const candidates=r.candidates||[],documents=r.documents||[];
    const candidateLabel={semantic:'向量检索',keyword:'全文检索',hybrid:'混合检索'}[r.mode]||'粗排';
    const candidateRanks=new Map(candidates.map((d,i)=>[d.id,i+1]));
    const rerankApplied=r.rerankMs>0;
    return `<div class="panel"><div class="row"><div><h2>召回结果</h2><p class="sub">总耗时 ${r.totalMs}ms · 向量 ${r.vectorMs}ms · 全文 ${r.keywordMs}ms · 精排 ${r.rerankMs}ms</p></div><div class="actions">${documents.length?button('recall-save','','保存为评测 Case'):''}${status(r.status)}</div></div>${r.notices.map(n=>`<div class="notice">${esc(n)}</div>`).join('')}<section class="recall-final"><div class="section-heading"><div><h2>最终结果（Top ${documents.length}）</h2><p class="sub">点击卡片查看Chunk原文和完整元信息。</p></div></div>${documents.map((d,i)=>{const rawRank=candidateRanks.get(d.id);const finalRank=i+1;const rankChange=!rerankApplied||!rawRank?'':rawRank>finalRank?`粗排 #${rawRank} → #${finalRank}`:rawRank<finalRank?`粗排 #${rawRank} → #${finalRank}`:`保持 #${finalRank}`;return recallCard(d,finalRank,rankChange);}).join('')||'<p class="muted">当前范围内没有可用结果。</p>'}</section><details class="recall-stage"><summary>粗排候选 · ${candidateLabel} Top ${candidates.length}</summary><div><p class="sub">进入精排前的原始候选，分数仅表示排序信号。</p>${candidates.map((d,i)=>recallCard(d,i+1)).join('')||'<p class="muted">当前范围内没有可用候选。</p>'}</div></details></div>`;
  }
  async function agentForm(a){const models=await api('/api/platform/models');const c=a?.config||{name:'',description:'',instructions:'优先参考相关资料回答用户问题，按配置允许通识解释和分析。内部事实、实时信息和指定文档内容必须有依据；查证不足时如实说明。',greeting:'请描述你要查询或分析的问题。',knowledgeBaseIds:[],toolSetIds:['knowledge-tools'],schemaVersion:2,strategy:'react',allowGeneralKnowledge:true,maxToolCalls:8,timeoutSeconds:120,temperature:0.1,candidateTopK:20,topK:10,maxOutputTokens:4000};
    dialog(a?'编辑智能体':'创建智能体',`
      ${field('名称','name',c.name,'text','required maxlength="160"')}
      ${field('描述','description',c.description,'text','maxlength="500"')}
      <label>任务说明（注入到 System Prompt）<textarea name="instructions" rows="5" required maxlength="12000">${esc(c.instructions)}</textarea></label>
      ${field('欢迎语','greeting',c.greeting,'text','maxlength="500"')}

      <label>文本 LLM
        <select name="chatModel" required>${options(models.chatModels,c.chatModel||models.chat)}</select>
        <small class="sub">用于本智能体的意图识别、规划、工具决策与回答。通过阿里百炼调用；Embedding 和 Rerank 不受影响。</small>
      </label>

      <div class="form-grid">
        <label>行为模式
          <select name="strategy">
            <option value="react" ${c.strategy==='react'?'selected':''}>ReAct</option>
            <option value="workflow" ${c.strategy==='workflow'?'selected':''}>Workflow</option>
            <option value="plan_execute_replan" ${c.strategy==='plan_execute_replan'?'selected':''}>Plan–Execute–Replan</option>
          </select>
          <small class="sub">ReAct 自主选工具；Workflow 调查告警并生成固定报告；Plan–Execute–Replan 按目标执行并调整计划。所有模式共用意图识别与证据审校。</small>
        </label>
      </div>

      <label><span><input type="checkbox" name="allowGeneralKnowledge" ${c.allowGeneralKnowledge?'checked':''} ${c.strategy==='workflow'?'disabled':''}> 允许通识补充</span>
        <small class="sub">允许补充一般知识和分析；关闭后知识性回答仅依据资料。内部事实与实时状态仍须查证。Workflow 始终严格取证。</small>
      </label>

      <label>知识库
        <small class="sub">勾选一个或多个知识库；运行只能检索这些资料</small>
      </label>
      ${checks('knowledgeBaseIds',catalog.knowledgeBases,c.knowledgeBaseIds)}

      <p class="sub">内置只读工具默认可用，包含时间、知识检索、原文和会话材料读取。</p>
      ${catalog.toolSets.some(t=>t.id!=='knowledge-tools')?'<label>额外工具授权</label>'+checks('toolSetIds',catalog.toolSets.filter(t=>t.id!=='knowledge-tools'),c.toolSetIds):''}

      <details>
        <summary>高级设置 - 运行预算与模型参数</summary>
        <div class="form-grid">
          ${field('最大工具调用','maxToolCalls',c.maxToolCalls,'number','min="1" max="16"')}
          ${field('时间上限（秒）','timeoutSeconds',c.timeoutSeconds,'number','min="10" max="300"')}
          ${field('Temperature','temperature',c.temperature,'number','min="0" max="1" step="0.1"')}
          ${field('输出 Token 上限','maxOutputTokens',c.maxOutputTokens,'number','min="256" max="8000"')}
          ${field('粗排候选数','candidateTopK',c.candidateTopK,'number','min="1" max="100"')}
          ${field('精排返回数','topK',c.topK,'number','min="1" max="10"')}
        </div>
      </details>
      <p class="sub" style="margin-top:16px">保存后新会话使用新版本，已有会话保留原配置。</p>
    `,async f=>{const body=Object.fromEntries(f);body.allowGeneralKnowledge=body.strategy!=='workflow'&&f.has('allowGeneralKnowledge');body.knowledgeBaseIds=f.getAll('knowledgeBaseIds');body.toolSetIds=['knowledge-tools',...f.getAll('toolSetIds')];body.schemaVersion=2;for(const name of ['maxToolCalls','timeoutSeconds','temperature','maxOutputTokens','candidateTopK','topK'])body[name]=+body[name];const saved=await api(base+'/agents'+(a?'/'+a.id:''),{method:a?'PUT':'POST',body});toast(a?'v'+saved.version+' 已保存并生效，新会话将使用 '+saved.config.strategy:'智能体已创建');await load();});
    const strategy=$('[name="strategy"]'),general=$('[name="allowGeneralKnowledge"]');
    let generalPreference=general.checked;
    strategy.addEventListener('change',()=>{
      if(strategy.value==='workflow'){generalPreference=general.checked;general.checked=false;general.disabled=true;}
      else if(general.disabled){general.disabled=false;general.checked=generalPreference;}
    });
  }
  function datasetForm(d){dialog(d?'编辑数据集':'新建数据集',`${field('名称','name',d?.name||'','text','required')}${field('说明','description',d?.description||'')}<label>父数据集<select name="parentId"><option value="">顶层数据集</option>${options(catalog.datasets.filter(x=>x.id!==d?.id),d?.parentId)}</select></label>`,async f=>{const body=Object.fromEntries(f);body.parentId||=null;await api(base+'/datasets'+(d?'/'+d.id:''),{method:d?'PUT':'POST',body});await load();});}
  function kbForm(k){const mode=k?.retrievalMode||'hybrid';dialog(k?'配置知识库':'新建知识库',`${field('名称','name',k?.name||'','text','required')}${field('说明','description',k?.description||'')}<label>默认召回模式<select name="retrievalMode"><option value="semantic" ${mode==='semantic'?'selected':''}>语义检索（向量）</option><option value="keyword" ${mode==='keyword'?'selected':''}>全文检索（BM25）</option><option value="hybrid" ${mode==='hybrid'?'selected':''}>混合检索（向量 + BM25）</option></select></label><p class="sub">文档存储由系统自动管理；保存后即可直接上传文档。召回测试与评测可做临时对照，不会改变这里的默认模式。</p>`,async f=>{await api(base+'/knowledge-bases'+(k?'/'+k.id:''),{method:k?'PUT':'POST',body:{name:f.get('name'),description:f.get('description'),retrievalMode:f.get('retrievalMode')||mode,datasetIds:[]}});await load();});}
  function toolsetForm(t){dialog(t?'编辑工具集':'创建工具集',`${field('名称','name',t?.name||'','text','required')}${field('说明','description',t?.description||'')}${checks('toolIds',catalog.tools.filter(x=>x.readOnly&&x.id.startsWith('mcp:')),t?.toolIds||[])}`,async f=>{await api(base+'/tool-sets'+(t?'/'+t.id:''),{method:t?'PUT':'POST',body:{name:f.get('name'),description:f.get('description'),toolIds:f.getAll('toolIds')}});await load();});}
  function mcpForm(c){dialog(c?'配置 MCP 连接':'添加 MCP 连接',`${field('名称','name',c?.name||'','text','required')}${field('Streamable HTTP 地址','endpoint',c?.endpoint||'','url','required placeholder="http://localhost:3001/mcp"')}${field('凭据环境变量名称（可选）','credentialEnv',c?.credentialEnv||'','text','placeholder="MY_MCP_TOKEN"')}<label><input name="enabled" type="checkbox" ${c?.enabled!==false?'checked':''}> 启用连接</label><p class="sub">请勿把密码或令牌粘贴到地址或名称中。保存后点击“发现工具”。</p>`,async f=>{await api(base+'/mcp'+(c?'/'+c.id:''),{method:c?'PUT':'POST',body:{...Object.fromEntries(f),enabled:f.has('enabled')}});await load();});}
  async function action(name,id,data={}){switch(name){
    case 'agent-versions':{const versions=await api(base+'/agents/'+id+'/versions');return dialog('配置版本',`<p class="sub">保存配置后立即生效。旧会话保留原版本；升级前版本仅供查看，请新建会话使用当前模式。</p>${table(['版本','状态'],versions.map(a=>`<tr><td>v${a.version} · ${esc(a.config.strategy)}</td><td>${a.current?'当前使用':'历史版本'}</td></tr>`))}<label>切换到<select name="version">${versions.filter(a=>a.config.schemaVersion===2).map(a=>`<option value="${a.version}" ${a.current?'selected':''}>v${a.version} · ${esc(a.config.name)} · ${esc(a.config.strategy)}</option>`).join('')}</select></label>${details('历史配置',versions)}`,async f=>{const active=await api(base+'/agents/'+id+'/activate',{method:'POST',body:{version:+f.get('version')}});toast('已切换到 v'+active.version+'，新会话将使用 '+active.config.strategy);await load();},'设为当前版本');}
    case 'agent-new':return agentForm();case 'agent-edit':return agentForm(catalog.agents.find(x=>x.id===id));
    case 'agent-toggle':await api(base+'/agents/'+id+'/enabled',{method:'PUT',body:{enabled:!catalog.agents.find(a=>a.id===id).enabled}});return load();
    case 'agent-history-delete':return dialog('删除全部对话记录','<p>将永久删除这个智能体的全部会话、运行过程、模型调用记录和会话附件。智能体会同时停用，之后才可删除智能体。</p>',async()=>{const result=await api(base+'/agents/'+id+'/sessions',{method:'DELETE'});toast(`已删除 ${result.sessions} 个会话和 ${result.runs} 条运行记录`);await load();},'确认删除记录');
    case 'agent-delete':return dialog('删除智能体','<p>将永久删除这个智能体及其全部配置版本。只有对话记录已清空时才能执行。</p>',async()=>{await api(base+'/agents/'+id,{method:'DELETE'});toast('智能体已删除');await load();},'确认删除智能体');
    case 'kb-new':return kbForm();case 'kb-edit':return kbForm(catalog.knowledgeBases.find(x=>x.id===id));
    case 'kb-delete':return dialog('删除知识库','<p>将从工作空间移除此知识库。仅属于它的文档会停止检索，原文和历史版本继续保留；仍被智能体使用时不会删除。</p>',async()=>{const result=await api(base+'/knowledge-bases/'+id,{method:'DELETE'});toast(`知识库已删除，${result.documents} 篇文档已退出检索`);localStorage.removeItem('expandedKb');await load();},'确认删除知识库');
    case 'doc-preview':return previewDoc(id);
    case 'doc-delete':return dialog('移除文档','<p>文档会退出新会话的检索范围。原版本仍保留，供历史报告追溯。</p>',async()=>{await api(base+'/documents/'+id,{method:'DELETE'});await load();});
    case 'doc-versions':{const versions=await api(base+'/documents/'+id+'/versions');dialog('原文与索引版本',table(['版本','状态','分块','原文'],versions.map(v=>`<tr><td>${esc(v.version)}</td><td>${status(v.status)}</td><td>${v.chunkCount}</td><td><button data-source="${esc(id)}" data-version="${esc(v.version)}">读取原文</button></td></tr>`)));return;}
    case 'toolset-new':return toolsetForm();case 'toolset-edit':return toolsetForm(catalog.toolSets.find(x=>x.id===id));
    case 'mcp-new':return mcpForm();case 'mcp-edit':return mcpForm(catalog.mcp.find(x=>x.id===id));
    case 'mcp-discover':{const c=await api(base+'/mcp/'+id+'/discover',{method:'POST'});toast(c.status==='CONNECTED'?`已发现 ${c.tools.length} 个工具`:c.error,c.status!=='CONNECTED');return load();}
    case 'tool-schema':{const t=catalog.tools.find(x=>x.id===id);return dialog(t.title,`<p>${esc(t.description)}</p><pre>${esc(JSON.stringify(t.schema,null,2))}</pre>`);}
    case 'tool-debug':return debugTool();
    case 'recall-save':return saveRecallCase();
    case 'eval-workspace':evalWorkspaceId=id;return load();
    case 'eval-back':evalWorkspaceId='';return load();
    case 'eval-generate':return generateEvalSet(id);
    case 'eval-import':return importEval(id,data.agent);case 'eval-start':return startEval(id,data.agent,data.kb);
    case 'eval-agent-build':return buildAgentEval(id,data.agent,data.strategy);
    case 'eval-set':{const s=window.platformEvalSets.find(x=>x.id===id);return dialog(s.name,renderEvalCases(s));}
    case 'eval-case-generate':return generateEvalSet(data.kb,id);
    case 'eval-case-delete':{const s=window.platformEvalSets.find(x=>x.id===id),item=s?.cases.find(x=>x.id===data.case);if(!s||!item)throw new Error('评测 Case 不存在');return dialog('删除 Case',`<p>确认删除以下 Case？历史运行结果仍保留，但当前基线会变为待重新评测。</p><div class="notice">${esc(item.question)}</div>`,async()=>{await api(base+'/evaluation/sets/'+encodeURIComponent(id)+'/cases/'+encodeURIComponent(data.case),{method:'DELETE'});toast('Case 已删除');await load();},'确认删除');}
    case 'eval-report':return openEvalReport(id);
    case 'eval-report-case':if(data.case)return selectEvalReportCase(id,data.case);return;
    case 'eval-report-back':return leaveEvalReport(data.kb);
    case 'eval-job':return evalJobs.find(j=>j.id===id)?.config?.agentId?openEvalReport(id):showEvalJob(id);
    case 'eval-retry':return dialog('重试异常 Case','<p>只重新执行本次实验中“执行失败”或“未判分”的 Case。完成后会生成一条新的合并结果，原始实验仍保留。</p><p class="sub">答案本身未通过的 Case 不会自动重试，避免用重复抽样美化质量分数。</p>',async()=>{await api(base+'/evaluation/jobs/'+id+'/retry-incomplete',{method:'POST'});toast('异常 Case 已提交补跑');await load();},'开始补跑');
    case 'eval-cancel':await api(base+'/evaluation/jobs/'+id+'/cancel',{method:'POST'});return load();
    case 'eval-compare':return compareEval();
    case 'run-details':{let all=[],after=0,batch;do{batch=await api(base+'/runs/'+id+'/events?after='+after);all.push(...batch);after=batch.at(-1)?.sequence||after;}while(batch.length===200);return dialog('运行过程',all.map(e=>details('#'+e.sequence+' · '+e.type,e.data)).join('')||'<p>尚无事件</p>');}
  }}
  async function previewDoc(id){const requestId=crypto.randomUUID();const el=dialog('文档预览',`<div id="previewContent" data-preview-request="${requestId}" class="empty">读取文档内容…</div>`);const content=()=>el.querySelector(`#previewContent[data-preview-request="${requestId}"]`);try{const d=await api(base+'/documents/'+id+'/preview');const target=content();if(!target)return;target.className='';target.innerHTML=`${d.truncated?'<div class="notice">预览已截断，可下载完整文档。</div>':''}<pre>${esc(d.content)}</pre>`;}catch(e){const target=content();if(target){target.className='error-box';target.textContent=e.message||String(e);}}}
  function debugTool(){const el=dialog('调试已绑定工具',`<label>智能体<select id="debugAgent">${options(catalog.agents)}</select></label><label>工具<select id="debugTool">${options(catalog.tools.map(t=>({...t,name:t.title})))}</select></label><label>参数 JSON<textarea id="debugArgs" rows="6">{"query":"DEXINED"}</textarea></label><button id="debugRun" type="button" class="primary">运行工具</button><div id="debugResult"></div>`);el.querySelector('#debugRun').onclick=async e=>{e.target.disabled=true;try{const r=await api(base+'/tools/debug',{method:'POST',body:{agentId:$('#debugAgent').value,toolId:$('#debugTool').value,arguments:JSON.parse($('#debugArgs').value)}});$('#debugResult').innerHTML=details('工具实际返回',r);}catch(err){$('#debugResult').innerHTML=`<div class="error-box">${esc(err.message)}</div>`;}finally{e.target.disabled=false;}};}
  async function saveRecallCase(){
    if(!lastRecall?.result?.documents?.length)throw new Error('请先完成一次召回测试');
    const sets=(await api(base+'/evaluation/sets')).filter(s=>s.kind==='retrieval'),hits=lastRecall.result.documents;
    dialog('保存为召回 Case',`<p class="notice">召回 Case 不需要参考答案。它验证的是：这个问题能否命中你确认的目标 Chunk。</p><label>保存到<select name="setId"><option value="">新建评测集</option>${options(sets)}</select></label>${field('新评测集名称','setName','人工确认的召回 Case','text','maxlength="160"')}<label>用户问题<textarea name="question" rows="3" required maxlength="8000">${esc(lastRecall.request.query)}</textarea></label><fieldset class="case-targets"><legend>选择正确的目标 Chunk</legend>${hits.map((d,i)=>`<label><input type="checkbox" name="target" value="${i}" ${i===0?'checked':''}><span><strong>#${i+1} ${esc(d.title||d.sourceFile)}</strong><small>${esc(d.sourceFile)} · ${esc(d.id)}</small></span></label>`).join('')}</fieldset>`,async f=>{
      const selected=f.getAll('target').map(Number).map(i=>hits[i]).filter(Boolean);if(!selected.length)throw new Error('至少选择一个目标Chunk');
      const item={id:'manual-'+crypto.randomUUID().slice(0,8),question:f.get('question'),expectedChunkIds:selected.map(d=>d.id),expectedSources:[...new Set(selected.map(d=>d.sourceFile))],requiredEvidence:[],referenceAnswer:'',origin:'manual',reviewStatus:'confirmed',knowledgeBaseId:lastRecall.knowledgeBaseId};
      if(f.get('setId'))await api(base+'/evaluation/sets/'+f.get('setId')+'/cases',{method:'POST',body:item});else await api(base+'/evaluation/sets',{method:'POST',body:{name:f.get('setName'),kind:'retrieval',knowledgeBaseId:lastRecall.knowledgeBaseId,cases:[item]}});
      toast('召回 Case 已保存');
    },'保存 Case');
  }
  function generateEvalSet(knowledgeBaseId='',setId=''){
    const available=catalog.knowledgeBases.filter(k=>k.datasetIds.length>0);if(!available.length)throw new Error('请先创建并上传知识库文档');
    const selected=available.find(k=>k.id===knowledgeBaseId),targetSet=window.platformEvalSets?.find(s=>s.id===setId);
    const excluded=targetSet?.cases.flatMap(c=>c.expectedChunkIds||[])||[];
    const excludedQuestions=targetSet?.cases.map(c=>c.question).filter(Boolean)||[];
    dialog(setId?'继续生成召回 Case':'从知识库生成召回 Case',`<p class="sub">模型只负责生成候选问题和参考答案；目标 Chunk、版本与证据由系统固定。保存前必须人工确认。</p>${targetSet?`<div class="fixed-field"><span>追加到</span><strong>${esc(targetSet.name)}</strong></div>`:''}${selected?`<input type="hidden" name="knowledgeBaseId" value="${esc(selected.id)}"><div class="fixed-field"><span>知识库</span><strong>${esc(selected.name)}</strong></div>`:`<label>知识库<select name="knowledgeBaseId">${options(available)}</select></label>`}<div class="form-grid">${field('生成数量','count',6,'number','min="1" max="10"')}<label>问题语言<select name="language"><option value="source">跟随资料</option><option value="zh">中文</option><option value="en">英文</option></select></label></div>${excluded.length?`<p class="sub">已排除当前 ${excluded.length} 个目标 Chunk，避免重复出题。</p>`:''}`,async f=>{
      const kbId=f.get('knowledgeBaseId'),cases=await api(base+'/evaluation/generate/retrieval',{method:'POST',body:{knowledgeBaseId:kbId,count:+f.get('count'),language:f.get('language'),excludeChunkIds:excluded,excludeQuestions:excludedQuestions}});const kb=catalog.knowledgeBases.find(k=>k.id===kbId);setTimeout(()=>reviewGeneratedCases(kb,cases,setId),0);
    },'生成候选');
  }
  function reviewGeneratedCases(kb,cases,setId=''){
    const targetSet=window.platformEvalSets?.find(s=>s.id===setId);
    dialog('确认生成的 Case',`${targetSet?`<div class="fixed-field"><span>追加到</span><strong>${esc(targetSet.name)}</strong></div>`:field('评测集名称','name',kb.name+' · 召回基线','text','required maxlength="160"')}<p class="notice">只有勾选并保存的案例才进入正式评测集。参考答案用于人工判断问题是否合理，召回指标只使用目标Chunk和证据。</p><div class="generated-cases">${cases.map((c,i)=>`<section><label class="generated-select"><input type="checkbox" name="include" value="${i}" checked> Case ${i+1} · ${esc(c.expectedSources[0])}</label><label>用户问题<textarea name="question_${i}" rows="2" required>${esc(c.question)}</textarea></label><label>参考答案<textarea name="answer_${i}" rows="3" required>${esc(c.referenceAnswer)}</textarea></label><details><summary>目标Chunk与证据原文</summary><div><code>${esc(c.expectedChunkIds[0])}</code><blockquote>${esc(c.requiredEvidence[0])}</blockquote></div></details></section>`).join('')}</div>`,async f=>{
      const selected=f.getAll('include').map(Number);if(!selected.length)throw new Error('至少确认一个Case');const confirmed=selected.map(i=>({...cases[i],question:f.get('question_'+i),referenceAnswer:f.get('answer_'+i),reviewStatus:'confirmed'}));if(setId)await api(base+'/evaluation/sets/'+encodeURIComponent(setId)+'/cases',{method:'POST',body:confirmed});else await api(base+'/evaluation/sets',{method:'POST',body:{name:f.get('name'),kind:'retrieval',knowledgeBaseId:kb.id,cases:confirmed}});toast(`已${setId?'追加':'保存'} ${confirmed.length} 个确认 Case`);evalWorkspaceId=kb.id;await load();
    },'确认并保存');
  }
  async function buildAgentEval(knowledgeBaseId,agentId,strategy){const agent=catalog.agents.find(a=>a.id===agentId),workflow=strategy==='workflow';dialog(workflow?'建立事故诊断 Case':'建立知识回答 Case',`<div class="fixed-field"><span>目标 Agent</span><strong>${esc(agent?.config.name||agentId)} v${agent?.version||'—'} · ${esc(strategyName(strategy))}</strong></div><p class="notice">${workflow?'系统会把 12 个模拟场景的告警、日志、时间和 Runbook 标注冻结到 Case；后续修改 Mock 文件不会改变本次基线。':'系统会继承当前已确认召回 Case 的问题、参考答案、目标 Chunk 与证据；不再让模型重新出题。'}</p><p class="sub">Case 可跨同一策略的 Agent 版本复用；Prompt、参数或工具升级后，旧分数自动标记为过期。</p>`,async()=>{await api(base+`/evaluation/generate/${workflow?'agent-workflow':'agent-knowledge'}`,{method:'POST',body:{knowledgeBaseId,agentId}});toast(workflow?'12 个事故 Case 已冻结':'知识回答 Case 已建立');evalWorkspaceId=knowledgeBaseId;await load();},'确认建立');}
  function snapshotCase(c){if(!c.incidentSnapshot)return '';let alerts={},logs={};try{alerts=JSON.parse(c.incidentSnapshot.alertsJson)}catch{}try{logs=JSON.parse(c.incidentSnapshot.logsJson)}catch{}return `<section class="frozen-snapshot"><h3>冻结事故快照</h3><div class="snapshot-meta"><span>${esc(c.displayName||c.incidentSnapshot.scenarioName)}</span><code>${esc(c.incidentSnapshot.id)}</code><span>${esc(c.incidentSnapshot.capturedAt)}</span></div>${details('告警原文',alerts.alerts||[])}${details('日志原文',logs.logs||[])}</section>`;}
  function renderEvalCases(s){const kbId=s.cases.find(c=>c.knowledgeBaseId)?.knowledgeBaseId||'';return `<div class="case-manager-head"><div class="case-summary"><span class="tag">${s.kind==='retrieval'?'召回评测':'Agent 任务'}</span><strong>${s.cases.length} 个 Case</strong></div>${s.kind==='retrieval'&&kbId?`<button type="button" class="primary" data-action="eval-case-generate" data-id="${esc(s.id)}" data-kb="${esc(kbId)}">＋ 继续生成 Case</button>`:''}</div><p class="sub">修改 Case 后，现有分数会标记为已过期，需要重新运行评测。</p><div class="case-list">${s.cases.map((c,i)=>`<details><summary><span>#${i+1}</span><strong>${esc(c.displayName||c.question)}</strong></summary><div><p class="case-question">${esc(c.question)}</p><dl class="case-meta"><dt>Case 来源</dt><dd>${esc(({derived_retrieval:'已确认召回 Case',frozen_scenario:'冻结事故场景',synthetic:'旧版生成（请复核证据）',synthetic_grounded_v2:'生成并审核原文支持（v2）'})[c.origin]||c.origin||'导入')}</dd><dt>目标 Chunk</dt><dd>${esc(c.expectedChunkIds?.join(', ')||'—')}</dd><dt>目标文档</dt><dd>${esc(c.expectedSources?.join(', ')||'—')}</dd><dt>必须工具</dt><dd>${esc(c.requiredTools?.join(', ')||'—')}</dd></dl>${snapshotCase(c)}${c.referenceAnswer?`<h3>参考答案 / 验收要点</h3><p>${esc(c.referenceAnswer)}</p>`:''}${c.requiredEvidence?.length?`<details><summary>必要证据 · ${c.requiredEvidence.length} 条</summary><blockquote>${esc(c.requiredEvidence.join('\n\n'))}</blockquote></details>`:''}<div class="case-item-actions"><button type="button" class="danger" data-action="eval-case-delete" data-id="${esc(s.id)}" data-case="${esc(c.id)}">删除 Case</button></div></div></details>`).join('')}</div>`;}
  function importEval(knowledgeBaseId='',agentId=''){const kb=catalog.knowledgeBases.find(k=>k.id===knowledgeBaseId),agent=catalog.agents.find(a=>a.id===agentId);dialog('导入固定 Case',`${field('评测集名称','name','','text','required')}${agent?`<div class="fixed-field"><span>目标 Agent</span><strong>${esc(agent.config.name)} v${agent.version} · ${esc(strategyName(agent.config.strategy))}</strong></div>`:'<label>类型<select name="kind"><option value="agent">Agent 任务评测</option><option value="retrieval">召回评测</option></select></label>'}${kb?`<div class="fixed-field"><span>归属知识库</span><strong>${esc(kb.name)}</strong></div>`:''}<label>JSON / JSONL 文件<input name="file" type="file" accept=".json,.jsonl" required></label><p class="sub">评测集绑定 Agent 身份和执行策略。后续 Prompt、工具或参数升级可复用 Case；策略改变后需为新策略建立 Case。</p>`,async f=>{const file=f.get('file');if(file.size>1500000)throw new Error('评测文件最大1.5MB');const raw=await file.text();let cases;try{cases=JSON.parse(raw);}catch{cases=raw.split(/\r?\n/).filter(x=>x.trim()).map(x=>JSON.parse(x));}if(!Array.isArray(cases))throw new Error('文件内容必须是 Case 数组或 JSONL');if(knowledgeBaseId)cases=cases.map(c=>({...c,knowledgeBaseId:c.knowledgeBaseId||knowledgeBaseId}));await api(base+'/evaluation/sets',{method:'POST',body:{name:f.get('name'),kind:agent?'agent':f.get('kind'),knowledgeBaseId,targetAgentId:agent?.id||'',targetStrategy:agent?.config.strategy||'',cases}});evalWorkspaceId=knowledgeBaseId;await load();});}
  function startEval(id,agentId='',knowledgeBaseId=''){const s=window.platformEvalSets.find(x=>x.id===id);if(!s)throw new Error('评测 Case 不存在');const linked=s.cases.find(c=>c.knowledgeBaseId)?.knowledgeBaseId,selectedKb=catalog.knowledgeBases.find(k=>k.id===(knowledgeBaseId||linked))||catalog.knowledgeBases[0],agent=catalog.agents.find(a=>a.id===agentId);dialog('运行 '+s.name,s.kind==='agent'?`<div class="fixed-field"><span>Agent</span><strong>${esc(agent?.config.name||agentId)} v${agent?.version||'—'}</strong></div><p class="sub">每个 Case 都会调用当前 Agent，并检查任务结果、证据与工具约束。</p>`:`<div class="fixed-field"><span>知识库</span><strong>${esc(selectedKb?.name||'—')}</strong></div><p class="sub">默认使用平台推荐检索配置；需要对照实验时再展开修改。</p><details class="advanced-settings"><summary>高级：检索配置</summary><label>模式<select name="mode"><option value="semantic" ${selectedKb?.retrievalMode==='semantic'?'selected':''}>语义</option><option value="keyword" ${selectedKb?.retrievalMode==='keyword'?'selected':''}>全文</option><option value="hybrid" ${selectedKb?.retrievalMode==='hybrid'?'selected':''}>混合</option></select></label><div class="form-grid">${field('粗排候选','candidateTopK',20,'number','min="1" max="100"')}${field('精排返回','topK',10,'number','min="1" max="10"')}</div><label><input name="rerank" type="checkbox" checked> 启用精排</label></details>`,async f=>{await api(base+'/evaluation/jobs',{method:'POST',body:{setId:id,config:{knowledgeBaseIds:selectedKb?[selectedKb.id]:[],mode:f.get('mode')||selectedKb?.retrievalMode||'semantic',candidateTopK:+f.get('candidateTopK')||20,topK:+f.get('topK')||10,rerank:s.kind==='agent'||f.has('rerank'),agentId:agent?.id||agentId||null,agentVersion:agent?.version||null}}});toast('评测已提交');evalWorkspaceId=selectedKb?.id||knowledgeBaseId;await load();},'开始评测');}
  function reviewDimensions(review){const names={answerCorrectness:'答案正确性',citationSupport:'引用支持',observationDiscipline:'观察与假设',actionSupport:'操作依据'};return review?.checks?table(['评审分项','结果','依据'],Object.entries(review.checks).map(([key,c])=>`<tr><td>${esc(names[key]||key)}</td><td>${esc(({passed:'通过',failed:'未通过',not_applicable:'不适用'})[c.status]||c.status)}</td><td>${esc(c.reason)}</td></tr>`)):'';}
  function evalRows(result,jobId=''){const rows=[...(result?.results||[])].sort((a,b)=>evalPassed(a)-evalPassed(b));if(!rows.length)return '<div class="empty">尚无案例结果</div>';return `<div class="result-heading"><h2>Case 结果</h2><span class="sub">执行失败和未通过优先</span></div>${rows.map(r=>{const passed=evalPassed(r),blocked=String(r.error||'').includes('DataInspectionFailed')||(r.turns||[]).some(t=>String(t.reviewError||'').includes('DataInspectionFailed')),label=blocked?'审核拦截':r.error?'执行失败':r.gradeStatus==='ungraded'?'未判分':passed?'通过':'未通过',url=`/console.html?evalJob=${encodeURIComponent(jobId)}&evalCase=${encodeURIComponent(r.id)}#evaluation`;return `<details class="eval-result ${passed?'passed':'failed'}" data-case-id="${esc(r.id)}"><summary><span class="tag ${passed?'':'error'}">${label}</span><strong>${esc(r.question)}</strong><span class="case-result-meta"><small>${r.elapsedMs||0} ms</small>${jobId?`<a href="${url}" title="打开此 Case 的固定链接">打开</a>`:''}</span></summary><div>${r.error?`<p class="error-box">${esc(r.error)}</p>`:''}${r.retrieval?`${r.labelWarning?`<p class="error-box">${esc(r.labelWarning)}</p>`:''}<p><b>定位阶段：</b>${esc(({candidate_miss:'粗排未召回目标',rerank_loss:'粗排命中，精排未保留',evidence_gap:'目标已命中，答案证据不全',retrieval_degraded:'检索降级，先检查服务',covered:'目标和标注证据已覆盖',alternative_evidence:'目标Chunk未命中，邻近原文包含标注证据'})[r.failureStage]||'旧版结果未记录阶段')} · <b>候选命中：</b>${r.candidateHit===undefined?'未记录':r.candidateHit?'是':'否'} · <b>扩展原文覆盖：</b>${r.contextEvidenceCovered===undefined?'未记录':r.contextEvidenceCovered?'通过':'未通过'}</p><p><b>目标排名：</b>${r.rank||'未命中'} · <b>目标召回：</b>${percent(r.targetRecall||0)} · <b>证据覆盖：</b>${r.requiredEvidenceCovered?'通过':'未通过'}</p>${details('实际召回结果',r.retrieval.documents)}`:''}${(r.turns||[]).map(t=>`<section class="turn-result"><h3>${esc(t.question)} · ${esc(t.gradeStatus)}</h3><div class="answer-preview">${esc(t.answer)}</div>${reviewDimensions(t.modelReview)}${details('规则检查与评审',{checks:t.checks,modelReview:t.modelReview,reviewError:t.reviewError})}${details('引用证据',t.evidence)}${details('实际用量',t.usage)}</section>`).join('')}</div></details>`;}).join('')}`;}
  async function showEvalJob(id,caseId=''){const j=await api(base+'/evaluation/jobs/'+id),url=`/console.html?evalJob=${encodeURIComponent(id)}#evaluation`,agentResult=Boolean(j.config?.agentId);const el=dialog('实验结果',`<div class="actions result-links"><a class="button" href="${url}">打开固定链接</a>${j.result?.retryOf?`<span class="sub">合并补跑 · 原实验 ${esc(j.result.retryOf)}</span>`:''}</div>${agentResult?agentResultScope(j.result?.summary):''}${metricCards(j.result?.summary,agentResult?'agent':'retrieval')}${evalRows(j.result,id)}${details('运行配置与固定版本',{config:j.config,model:j.result?.model,sourceVersions:j.result?.sourceVersions,retryOf:j.result?.retryOf,retriedCaseIds:j.result?.retriedCaseIds})}`);if(caseId){const target=[...el.querySelectorAll('[data-case-id]')].find(x=>x.dataset.caseId===caseId);if(target){target.open=true;setTimeout(()=>target.scrollIntoView({block:'start'}),50);}}return el;}
  const evalPassed=r=>r.error?false:r.retrieval?(r.rank>0&&r.requiredEvidenceCovered!==false):r.gradeStatus==='passed';
  function comparisonWarnings(a,b){
    const notes=[];if(a.setId!==b.setId)notes.push('案例集不同，不能直接用分数判断优劣。');
    if(a.result?.model&&b.result?.model&&a.result.model!==b.result.model)notes.push('模型不同，结果差异不能单独归因于执行策略。');
    const versions=j=>j.result?.sourceVersions?.map(d=>d.documentId+':'+d.version).sort().join('|');
    if(versions(a)!==undefined&&versions(b)!==undefined&&versions(a)!==versions(b))notes.push('知识来源版本不同，请先核对资料变化再比较策略效果。');
    return notes.map(n=>`<p class="notice">${esc(n)}</p>`).join('');
  }
  function compareEval(){if(evalJobs.length<2)throw new Error('至少需要两次评测结果');const jobs=evalJobs.map(j=>({...j,name:time(j.createdAt)+' · '+j.status+' · '+(j.config.agentId||j.config.mode)}));const el=dialog('比较评测配置与结果',`<div class="form-grid"><label>运行 A<select id="compareA">${options(jobs,jobs[0].id)}</select></label><label>运行 B<select id="compareB">${options(jobs,jobs[1].id)}</select></label></div><div id="comparison"></div>`);const render=()=>{const a=evalJobs.find(j=>j.id===$('#compareA').value),b=evalJobs.find(j=>j.id===$('#compareB').value);$('#comparison').innerHTML=`${comparisonWarnings(a,b)}<div class="comparison">${[a,b].map(j=>`<section><p>${metricSummary(j.result?.summary)||'尚未完成'}</p>${details('配置与来源',{config:j.config,model:j.result?.model,sourceVersions:j.result?.sourceVersions})}</section>`).join('')}</div>${table(['案例','A','B'],[...new Set([...(a.result?.results||[]),...(b.result?.results||[])].map(r=>r.id))].map(id=>`<tr><td>${esc(id)}</td>${[a,b].map(j=>{const r=j.result?.results?.find(x=>x.id===id);return `<td>${r?esc(r.gradeStatus||r.status)+' · '+(r.elapsedMs||0)+' ms'+details('答案与判分',r):'无对应案例'}</td>`;}).join('')}</tr>`))}`;};el.querySelectorAll('select').forEach(x=>x.onchange=render);render();}
  document.addEventListener('click',async e=>{const b=e.target.closest('[data-action]');if(b){b.disabled=true;try{await action(b.dataset.action,b.dataset.id,b.dataset);}catch(err){fail(err);}finally{b.disabled=false;}}const source=e.target.closest('[data-source]');if(source){try{const r=await api(base+'/documents/'+source.dataset.source+'/source?version='+encodeURIComponent(source.dataset.version)+'&offset='+(source.dataset.offset||0));const el=dialog('原文 · '+r.sourceFile,`<p class="sub">版本 ${esc(r.version)} · 字符 ${r.start}–${r.end} / ${r.totalChars}</p><pre>${esc(r.content)}</pre>${r.truncated?`<button data-source="${esc(r.documentId)}" data-version="${esc(r.version)}" data-offset="${r.nextOffset}">读取下一段</button>`:''}`);}catch(err){fail(err);}}});
  $('#refresh').onclick=load;window.addEventListener('hashchange',load);window.addEventListener('popstate',load);
  $('#logout').onclick=async()=>{await api('/api/auth/logout',{method:'POST'});location.href='/login.html';};$('#account').onclick=()=>Platform.account(me);
  $('#members').onclick=async()=>{try{const users=await api(base+'/users');dialog('成员管理',`${table(['账号','角色','状态'],users.map(u=>`<tr><td>${esc(u.username)}</td><td>${esc(u.role)}</td><td>${u.enabled?'启用':'停用'}</td></tr>`))}<h3 style="margin-top:20px">创建成员</h3>${field('用户名','username','','text','required minlength="3"')}${field('初始密码','password','','password','required minlength="12" autocomplete="new-password"')}`,async f=>{await api(base+'/users',{method:'POST',body:Object.fromEntries(f)});toast('成员已创建');});}catch(err){fail(err);}};
  auth(true).then(user=>{me=user;return load();}).catch(fail);
})();
