fetch('/build-info.json',{cache:'no-store'}).then(r=>r.json()).then(b=>{
  const built=new Date(b.builtAt);
  const label='本地版本 · '+new Intl.DateTimeFormat('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(built);
  const workspace=document.querySelector('.workspace-note');
  document.documentElement.dataset.build=b.sourceHash;
  if(workspace) workspace.title=label+'\n部署源码指纹 '+b.sourceHash;
}).catch(()=>{});
