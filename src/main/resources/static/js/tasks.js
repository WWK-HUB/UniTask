import {
  api, h, content, id, link, button, click, form, submit, field, input, textarea, select, card, empty, pill,
  statePill, sectionTitle, actions, row, feedback, dialog, closeDialog, confirmAction, picker, path, pager,
  formatTime, dateValue, deadlineField, dateInput, fileLinks, statusLabels, priorities, table
} from './ui.js';
import {canManage, collect} from './projects.js';

const states = {
  UNASSIGNED:statusLabels.UNASSIGNED, IN_PROGRESS:statusLabels.IN_PROGRESS, PENDING_REVIEW:statusLabels.PENDING_REVIEW,
  REWORK:statusLabels.REWORK, COMPLETED:statusLabels.COMPLETED
};
const commentLabels = {PROGRESS:'进度更新',DISCUSSION:'讨论',SUBMISSION:'提交验收'};
const logLabels = {CREATED:'创建任务',DELETED:'删除任务',TASK_UPDATED:'编辑任务',ASSIGNEES_CHANGED:'调整负责人',
  REVIEW_REQUESTED:'提交验收',REVIEW_APPROVED:'通过验收',REVIEW_REJECTED:'打回任务',REMINDER:'发送催办'};
const taskLink = task => link(task.title,'/tasks/' + task.id);
const due = task => task.deadline ? '截止 ' + formatTime(task.deadline) : '无截止时间';
const assignees = task => task.assignees.map(a => a.nickname).join('、') || '暂无负责人';
const capacity = task => task.assignedCount + ' / ' + task.requiredCount + ' 人';

function taskEditor(project, task, done) {
  const uploads = picker('任务附件');
  dialog(task ? '编辑任务' : '发布任务', form(async element => {
    const raw = new FormData(element);
    const body = {title:raw.get('title'),description:raw.get('description'),requiredCount:Number(raw.get('requiredCount')),
      priority:raw.get('priority'), deadline:task && raw.get('deadline') === dateValue(task.deadline)
        ? task.deadline : dateInput(raw.get('deadline'))};
    await api(task ? '/tasks/' + task.id : '/projects/' + project + '/tasks',
      {method:task ? 'PATCH' : 'POST',body,files:uploads.files});
    closeDialog(); await done();
  },
  field('任务标题',input('title',task?.title,{maxlength:100,required:true,autofocus:true})),
  field('工作内容',textarea('description',task?.description,{maxlength:10000,required:true})),
  h('div',{class:'field-pair'},
    field('所需人数',input('requiredCount',task?.requiredCount ?? 1,{type:'number',min:1,max:100,required:true})),
    field('优先级',select('priority',priorities,task?.priority ?? 'NORMAL'))),
  deadlineField(task?.deadline), !task && uploads.view, submit(task ? '保存任务' : '发布任务')));
}

function taskCard(task,reload) {
  return h('article',{class:'task-tile'},
    row(pill(priorities[task.priority],task.priority),task.overdue && pill('已逾期','late')),
    h('h3',{},taskLink(task)),
    h('small',{class:'block'},assignees(task)),
    h('div',{class:'tile-data'},h('span',{},capacity(task)),h('span',{},due(task))),
    task.canClaim && click('认领',async () => {
      await api('/tasks/' + task.id + '/claim',{method:'POST'});
      await reload(); feedback('已认领任务。');
    },'small-button'));
}
function taskTable(items,reload,isMine) {
  return table(['任务','状态','负责人 / 名额','优先级','截止时间','操作'],items.map(task =>
    [h('div',{},taskLink(task),isMine && h('small',{class:'block'},task.projectName)),
      row(statePill(task.status),task.overdue && pill('已逾期','late')),
      h('div',{},assignees(task),h('small',{class:'block'},capacity(task))),
      priorities[task.priority],formatTime(task.deadline),
      task.canClaim ? click('认领',async () => {
        await api('/tasks/' + task.id + '/claim',{method:'POST'});
        await reload(); feedback('已认领任务。');
      },'small-button') : link('详情','/tasks/' + task.id)]));
}

export async function projectTasks() {
  const [project,members] = await Promise.all([api('/projects/' + id),collect('/projects/' + id + '/members')]);
  const list = h('div',{class:'results'});
  const query = {};
  let board = true;
  const toggle = button('切换到列表',async event => {
    board = !board;
    event.currentTarget.textContent = board ? '切换到列表' : '切换到看板';
    await load();
  });
  const toolbar = h('div',{class:'toolbar'},
    form(async element => {
      Object.assign(query,Object.fromEntries(new FormData(element)));
      await load();
    },h('div',{class:'filters'},
      field('搜索任务',input('q','',{type:'search',placeholder:'任务标题',maxlength:100})),
      field('状态',select('status',states,'','全部状态')),
      field('负责人',select('assigneeId',Object.fromEntries(members.map(m => [m.id,m.nickname])),'','全部成员')),
      field('优先级',select('priority',priorities,'','全部优先级')),submit('筛选',''))),
    actions(toggle,canManage(project) && click('随机分配',() => allocationDialog(project,members,load)),
      canManage(project) && button('＋ 发布任务',() => taskEditor(id,null,async () => {
        await load(); feedback('任务已发布。');
      }), 'primary')));
  content.replaceChildren(toolbar,list);
  await load();

  async function load() {
    if (!board) return loadTable(0);
    const entries = Object.entries(states).filter(([state]) => !query.status || query.status === state);
    const lanes = entries.map(([key,label]) => h('section',{class:'lane ' + key.toLowerCase().replaceAll('_','-'),'aria-label':label},
      h('p',{class:'empty'},'正在加载…')));
    list.replaceChildren(h('div',{class:'board'},lanes));
    await Promise.all(entries.map(([state,label],index) => loadLane(state,label,lanes[index],0)));
  }
  async function loadLane(state,label,lane,page) {
    const result = await api('/projects/' + id + '/tasks',{query:{...query,status:state,page}});
    const nav = pager(result,next => loadLane(state,label,lane,next));
    lane.replaceChildren(sectionTitle(label,pill(result.total)),...(result.items.length
      ? result.items.map(task => taskCard(task,load)) : [empty('暂无任务')]),
      ...[nav].filter(Boolean));
    if (result.items.some(task => task.canClaim)) {
      lane.querySelectorAll('button').forEach(control => {
        if (control.textContent === '认领') control.title = '认领后任务会更新所属状态';
      });
    }
  }
  async function loadTable(page) {
    const result = await api('/projects/' + id + '/tasks',{query:{...query,page}});
    const nav = pager(result,loadTable);
    list.replaceChildren(result.items.length ? taskTable(result.items,() => loadTable(page),false)
      : empty('没有符合条件的任务'), ...[nav].filter(Boolean));
  }
}

async function allocationDialog(project,members,reload) {
  dialog('随机分配',empty('正在读取可分配任务…'));
  const eligible = (await collect('/projects/' + project.id + '/tasks'))
    .filter(t => Object.hasOwn(states,t.status) && !['COMPLETED','PENDING_REVIEW'].includes(t.status) && t.assignedCount<t.requiredCount);
  const users = Object.fromEntries(members.map(m => [m.id,m.nickname]));
  const byTask = Object.fromEntries(eligible.map(t => [t.id,t]));
  const selected = new Set(eligible.map(t => t.id));
  let includeLeader = false;
  function choose() {
    if (!eligible.length) {
      dialog('随机分配',empty('没有留有名额的可分配任务。'));
      return;
    }
    const chooser = h('fieldset',{class:'choice-list'},h('legend',{},'选择任务'),
      eligible.map(t => h('label',{class:'check-row'},h('input',{type:'checkbox',name:'taskIds',value:t.id,checked:selected.has(t.id)}),
        h('span',{},t.title,' · ',capacity(t)))));
    dialog('随机分配',h('p',{class:'dialog-copy'},'已有负责人会保留。优先将空缺分给当前未完成任务较少的成员。'),
      form(async element => {
        const values = new FormData(element);
        const ids = values.getAll('taskIds').map(Number);
        if (!ids.length) throw new Error('至少选择一项任务。');
        selected.clear();
        ids.forEach(task => selected.add(task));
        includeLeader = values.has('includeLeader');
        await generate();
      },chooser,h('label',{class:'check-row'},h('input',{type:'checkbox',name:'includeLeader',checked:includeLeader}), '组长也参与分配'),
      submit('生成预览')));
  }
  async function generate() {
    const preview = await api('/projects/' + project.id + '/allocations/preview',
      {method:'POST',body:{taskIds:[...selected],includeLeader}});
    const confirm = click('确认分配',async () => {
      try {
        await api('/projects/' + project.id + '/allocations/confirm',{method:'POST',body:{previewToken:preview.previewToken}});
      } catch(error) {
        if (error.status !== 409) throw error;
        dialog('分配预览已失效', h('p',{class:'dialog-copy'},error.message),
          actions(button('重新选择任务',() => allocationDialog(project,members,reload),'primary')));
        return;
      }
      closeDialog(); await reload(); feedback('分配已确认。');
    },'primary');
    const proposals = table(['任务','已分配','新增建议','仍缺'],preview.assignments.map(a =>
      [byTask[a.taskId]?.title || '#' + a.taskId, byTask[a.taskId] ? assignees(byTask[a.taskId]) : '—',
        a.newUserIds.map(member => users[member]).join('、') || '—',a.remainingSlots]));
    dialog('随机分配预览',h('p',{class:'notice'},'此预览 10 分钟内有效 · ' + formatTime(preview.expiresAt) + ' 到期'),
      proposals, h('p',{class:'muted'},'预计未完成任务量 · ' + preview.memberLoads.map(m =>
        users[m.userId] + ' ' + m.taskCount + ' 项').join('，')),
      actions(button('返回调整',choose),click('重新生成',generate),confirm));
  }
  choose();
}

export async function myTasks() {
  const projects = await collect('/projects');
  const query = {};
  const list = h('div',{});
  content.replaceChildren(card(form(async element => {
    Object.assign(query,Object.fromEntries(new FormData(element))); await load(0);
  },h('div',{class:'filters'},
    field('所属项目',select('projectId',Object.fromEntries(projects.map(p => [p.id,p.name])),'','全部项目')),
    field('状态',select('status',states,'','全部状态')),
    field('截止',select('overdue',{true:'仅逾期'},'','所有任务')),submit('筛选',''))),list));
  async function load(page) {
    const result = await api('/my/tasks',{query:{...query,page}});
    list.replaceChildren(result.items.length ? taskTable(result.items,() => load(page),true)
      : empty('没有符合条件的任务'), ...[pager(result,load)].filter(Boolean));
  }
  await load(0);
}

export async function detail() {
  const task = await api('/tasks/' + id);
  const taskPath = '/tasks/' + id;
  const refresh = async message => { await detail(); feedback(message); };
  const toolbar = actions(
    task.canClaim && click('认领任务',async () => { await api(taskPath + '/claim',{method:'POST'}); await refresh('已认领任务。'); },'primary'),
    task.canCancel && button('取消认领',() => confirmAction('取消认领','取消后其他成员可以认领此名额。','确认取消',async () => {
      await api(taskPath + '/claim',{method:'DELETE'}); closeDialog(); await refresh('已取消认领。');
    })),
    task.canEdit && button('编辑任务',() => taskEditor(task.projectId,task,() => refresh('任务已更新。'))),
    task.canEdit && click('调整负责人',() => assigneeDialog(task,refresh)),
    task.canEdit && task.status === 'UNASSIGNED' && !task.attachments.length &&
      button('删除空任务',() => confirmAction('删除任务','只可删除无分工或协作历史的待分配任务。','确认删除',async () => {
        await api(taskPath,{method:'DELETE'}); window.location.assign(path('/projects/' + task.projectId + '/tasks'));
      }),'danger-button'),
    task.canRemind && button('催办',() => remindDialog(task)),
    task.canReview && button('通过验收',() => confirmAction('通过验收','通过后任务将计入项目完成进度。','确认通过',async () => {
      await review(task,'APPROVED'); closeDialog(); await refresh('任务已验收。');
    }), 'primary'),
    task.canReview && button('打回',() => dialog('打回任务',form(async element => {
      await review(task,'REJECTED',new FormData(element).get('reason'));
      closeDialog(); await refresh('已打回并通知负责人。');
    },field('打回原因',textarea('reason','',{required:true,maxlength:5000,autofocus:true})),submit('确认打回','danger-button'))),'danger-button'));

  const timeline = h('div',{id:'timeline'});
  const logs = h('div',{});
  content.replaceChildren(
    h('article',{class:'task-hero'},row(statePill(task.status),pill(priorities[task.priority],task.priority),
      task.overdue && pill('已逾期','late')),
      h('h2',{},task.title), h('p',{class:'pre-line task-description'},task.description),
      h('dl',{class:'task-meta'},h('div',{},h('dt',{},'负责人'),h('dd',{},assignees(task))),
        h('div',{},h('dt',{},'分配名额'),h('dd',{},capacity(task))),h('div',{},h('dt',{},'截止时间'),h('dd',{},formatTime(task.deadline)))),
      task.attachments.length > 0 && h('div',{class:'original-files'},h('h3',{},'任务附件'),fileLinks(task.attachments)),toolbar),
    h('div',{class:'task-layout'},
      card(sectionTitle('协作时间线'),timeline,task.canComment && composer(task,refresh)),
      card(sectionTitle('操作记录'),logs)));

  const targetComment = /^#comment-(\d+)$/.exec(location.hash)?.[1];
  let commentPage = 0;
  if (targetComment) {
    try { commentPage = (await api(taskPath + '/comments/' + targetComment + '/page')).page; }
    catch (error) { if (error.status !== 404) throw error; }
  }
  await Promise.all([loadComments(commentPage),loadLogs(0)]);
  if (targetComment) document.getElementById('comment-' + targetComment)?.scrollIntoView({block:'center'});

  async function loadComments(page) {
    const result = await api(taskPath + '/comments',{query:{page}});
    timeline.replaceChildren(result.items.length ? h('ol',{class:'timeline'},result.items.map(commentItem)) : empty('暂无协作记录'),
      ...[pager(result,loadComments)].filter(Boolean));
  }
  async function loadLogs(page) {
    const result = await api(taskPath + '/logs',{query:{page,size:10}});
    logs.replaceChildren(result.items.length ? h('ol',{class:'audit'},result.items.map(entry => h('li',{},
      h('strong',{},logLabels[entry.action] || entry.action),h('small',{},entry.actorName + ' · ' + formatTime(entry.createdAt)))))
      : empty('暂无操作记录'), ...[pager(result,loadLogs)].filter(Boolean));
  }
}

function commentItem(comment) {
  return h('li',{id:'comment-' + comment.id,class:'comment'},
    h('div',{class:'comment-head'},h('span',{class:'avatar','aria-hidden':'true'},Array.from(comment.authorName)[0]),
      h('strong',{},comment.authorName),pill(commentLabels[comment.kind]),
      h('time',{datetime:comment.createdAt},formatTime(comment.createdAt))),
    comment.body && h('p',{class:'pre-line'},comment.body),
    fileLinks(comment.attachments),
    comment.review && h('div',{class:comment.review.decision === 'APPROVED' ? 'notice success' : 'notice danger'},
      h('strong',{},comment.review.decision === 'APPROVED' ? '验收通过' : '打回修改'),
      h('p',{class:'pre-line'},comment.review.reason),
      h('small',{},comment.review.reviewerName + ' · ' + formatTime(comment.review.reviewedAt))));
}

function composer(task,refresh) {
  const uploads = picker();
  return h('section',{class:'composer'},h('h3',{},'同步协作进展'),
    form(async (element,event) => {
      const submitting = event.submitter?.value === 'submit';
      const body = new FormData(element).get('body');
      const result = await api('/tasks/' + task.id + (submitting ? '/submit' : '/comments'),
        {method:'POST',body:{body},files:uploads.files});
      history.replaceState(null,'','#comment-' + (submitting ? result.submissionId : result.id));
      await refresh(submitting ? '成果已提交验收。' : '评论已发布。');
    },field('评论或成果说明',textarea('body','',{placeholder:'记录本次工作，或直接添加附件。',maxlength:5000})),
    uploads.view, actions(submit('发表评论'),task.isAssignee && h('button',{
      type:'submit',name:'operation',value:'submit',disabled:!task.canSubmit,
      title:task.canSubmit ? '将整项任务提交组长验收' : '任务满员且处于可提交状态时启用'
    },'提交验收'))));
}

async function assigneeDialog(task,refresh) {
  const members = await collect('/projects/' + task.projectId + '/members');
  const current = new Set(task.assignees.map(a => a.id));
  dialog('调整负责人',h('p',{class:'dialog-copy'},'最多选择 ' + task.requiredCount + ' 人。保存后将以所选成员作为完整负责人名单。'),
    form(async element => {
      await api('/tasks/' + task.id + '/assignees',{method:'PUT',body:{userIds:new FormData(element).getAll('userIds').map(Number)}});
      closeDialog(); await refresh('负责人已更新。');
    },h('fieldset',{class:'choice-list'},h('legend',{},'项目成员'),members.map(m =>
      h('label',{class:'check-row'},h('input',{type:'checkbox',name:'userIds',value:m.id,checked:current.has(m.id)}),m.nickname,
        h('small',{},'@' + m.username)))),submit('保存负责人')));
}

function remindDialog(task) {
  dialog('催办任务',h('p',{class:'dialog-copy'},'每位负责人每 5 分钟最多收到一次催办。'),
    form(async element => {
      const userIds = new FormData(element).getAll('userIds').map(Number);
      if (!userIds.length) throw new Error('请选择要提醒的负责人。');
      const {sent,skipped} = await api('/tasks/' + task.id + '/remind',{method:'POST',body:{userIds}});
      closeDialog(); feedback('已提醒 ' + sent.length + ' 人' + (skipped.length ? '，跳过 ' + skipped.length + ' 人。' : '。'));
    },h('fieldset',{class:'choice-list'},h('legend',{},'提醒负责人'),task.assignees.map(user =>
      h('label',{class:'check-row'},h('input',{type:'checkbox',name:'userIds',value:user.id,checked:true}),user.nickname,
        task.reminderHints?.includes(user.id) && h('small',{},'近期已提醒')))),submit('发送提醒')));
}

function review(task,decision,reason) {
  return api('/tasks/' + task.id + '/review',{method:'POST',body:{
    reviewRound:task.reviewRound,submissionId:task.currentSubmissionId,decision,reason}});
}
