import {
  api, h, content, id, link, button, click, form, submit, field, input, textarea, select, card, empty,
  pill, statePill, sectionTitle, actions, row, feedback, dialog, closeDialog, confirmAction, picker,
  path, pager, formatTime, dateValue, deadlineField, dateInput, fileLinks, table, fileSize
} from './ui.js';

const projectStatuses = {ACTIVE:'进行中', ARCHIVED:'已归档'};
const invitationStatuses = {PENDING:'待处理', ACCEPTED:'已接受', DECLINED:'已拒绝', REVOKED:'已撤销'};
export const canManage = project => project.role === 'LEADER' && project.status === 'ACTIVE';
const role = project => project.role === 'LEADER' ? '组长' : '组员';

function progress(project) {
  return h('section', {class:'progress-widget'},
    h('div', {class:'progress-caption'}, h('span', {}, project.taskCount
      ? '已验收 ' + project.completedCount + ' / ' + project.taskCount : '暂无任务'),
    h('strong', {}, project.progressPercent + '%')),
    h('progress', {value:project.progressPercent, max:100, 'aria-label':'项目验收进度'}));
}
function projectCard(project) {
  return h('article', {class:'project-card'},
    h('div', {class:'project-card-top'}, pill(project.course,'course'), statePill(project.status)),
    h('h2', {}, link(project.name,'/projects/' + project.id, 'card-link')),
    project.description && h('p', {class:'project-excerpt'}, project.description),
    progress(project),
    h('div', {class:'project-foot'}, h('span', {}, role(project)), h('span', {}, '截止 ' + formatTime(project.deadline))));
}

function projectEditor(existing, done) {
  const uploads = picker('参考附件');
  dialog(existing ? '编辑项目' : '创建项目', form(async element => {
    const fields = new FormData(element);
    const body = {name:fields.get('name'), course:fields.get('course'), description:fields.get('description'),
      deadline:existing && fields.get('deadline') === dateValue(existing.deadline) ? existing.deadline : dateInput(fields.get('deadline'))};
    const response = await api(existing ? '/projects/' + existing.id : '/projects', {
      method:existing ? 'PATCH' : 'POST', body, files:uploads.files
    });
    closeDialog();
    await done(response);
  },
  field('项目名称', input('name', existing?.name, {required:true, maxlength:100, autofocus:true})),
  field('课程', input('course', existing?.course, {required:true, maxlength:100})),
  field('项目简介', textarea('description', existing?.description, {maxlength:2000})),
  deadlineField(existing?.deadline), !existing && uploads.view,
  submit(existing ? '保存项目' : '创建项目')));
}
const newProject = () => button('＋ 创建项目', () => projectEditor(null, result => {
  window.location.assign(path('/projects/' + result.id));
}), 'primary');

export async function dashboard() {
  const [projects, tasks, notices, me] = await Promise.all([
    api('/projects',{query:{size:6}}), api('/my/tasks',{query:{size:8}}),
    api('/notifications',{query:{size:5}}), api('/me')
  ]);
  const open = tasks.items.filter(task => task.status !== 'COMPLETED');
  const projectsPanel = h('div',{class:'results'});
  const populate = result => projectsPanel.replaceChildren(
    result.items.length ? h('div',{class:'project-grid'},result.items.map(projectCard))
      : empty('你还没有加入项目。可以创建项目，或接受组员邀请。'),
    ...[pager(result,async page => populate(await api('/projects',{query:{size:6,page}})))].filter(Boolean));
  populate(projects);
  content.replaceChildren(
    h('div', {class:'welcome'}, h('div', {}, h('h2', {}, '你好，' + me.nickname),
      h('p', {}, '查看课程项目进展，继续处理待办任务。')), newProject()),
    sectionTitle('我的项目', link('查看所有项目','/projects')),
    projectsPanel,
    h('div', {class:'dashboard-lower'},
      card(sectionTitle('近期待办',link('我的任务','/my/tasks')), open.length
        ? h('ul', {class:'plain-list'}, open.map(task => h('li', {}, h('div', {},
          link(task.title,'/tasks/' + task.id), h('small', {}, task.projectName)), statePill(task.status),
          h('small', {}, formatTime(task.deadline))))) : empty('暂无待办任务')),
      card(sectionTitle('近期消息',link('消息中心','/notifications')), notices.items.length
        ? h('ul', {class:'plain-list'}, notices.items.map(notice => h('li', {},
          h('div', {}, h('a', {href:notice.link}, notice.title), h('small', {}, notice.summary)),
          h('small', {}, formatTime(notice.createdAt))))) : empty('暂无消息'))));
}

export async function projectsPage() {
  const filters = {status:'',q:''};
  const list = h('div', {class:'results'});
  const panel = form(async element => {
    const values = new FormData(element);
    filters.status = values.get('status');
    filters.q = values.get('q');
    await load(0);
  }, h('div', {class:'filters'},
    field('搜索项目', input('q','',{type:'search',placeholder:'项目名称或课程',maxlength:100})),
    field('状态', select('status',projectStatuses,'','全部项目')), submit('筛选','')));
  async function load(page) {
    const result = await api('/projects',{query:{...filters,page}});
    list.replaceChildren(result.items.length ? h('div', {class:'project-grid'}, result.items.map(projectCard))
      : empty('没有符合条件的项目。'), ...(pager(result,load) ? [pager(result,load)] : []));
  }
  content.replaceChildren(h('div', {class:'toolbar'}, panel, newProject()), list);
  await load(0);
}

export async function overview() {
  const [project, stats] = await Promise.all([api('/projects/' + id), api('/projects/' + id + '/stats')]);
  const controls = canManage(project) && actions(
    button('编辑项目', () => projectEditor(project, async () => { await overview(); feedback('项目已更新。'); })),
    button('归档项目', () => confirmAction('归档项目', '归档后仍可查看并下载历史成果，项目内协作将关闭。', '确认归档', async () => {
      await api('/projects/' + id + '/archive',{method:'POST'});
      closeDialog();
      await overview();
      feedback('项目已归档。');
    }), 'danger-button', {disabled:project.completedCount !== project.taskCount,
      title:project.completedCount !== project.taskCount ? '请先验收全部任务' : '归档后项目只读'}));
  content.replaceChildren(
    h('section',{class:'project-hero'}, h('div',{}, row(pill(project.course,'course'),pill(role(project)),statePill(project.status)),
      h('h2',{},project.name), h('p',{class:'pre-line'},project.description || '暂无项目简介')), controls),
    h('div',{class:'dashboard-lower'},
      card(sectionTitle('验收进度',link('查看任务','/projects/' + id + '/tasks')), progress(project),
        h('p',{class:'muted datum'},'项目截止 · ' + formatTime(project.deadline))),
      card(h('h2',{},'参考附件'), fileLinks(project.attachments) || empty('暂无项目参考附件'))),
    card(h('h2',{},'成员参与'),
      table(['成员','当前负责','已完成','评论','附件'],stats.members.map(m =>
        [m.nickname,m.assignedCount,m.completedCount,m.commentCount,m.attachmentCount]))));
}

export async function membersPage() {
  const project = await api('/projects/' + id);
  const editable = canManage(project);
  const list = h('div', {});
  const invitations = h('div', {});
  const inviteButton = editable && button('＋ 邀请成员', openInvite, 'primary');
  content.replaceChildren(card(sectionTitle('已加入成员',inviteButton),list),
    ...(project.role === 'LEADER' ? [card(h('h2',{},'发出的邀请'),invitations)] : []));
  await loadMembers(0);
  if (project.role === 'LEADER') await loadInvitations(0);

  function openInvite() {
    let candidate;
    const preview = h('p',{class:'notice',hidden:true});
    const send = click('发出邀请',async () => {
      await api('/projects/' + id + '/invitations',{method:'POST',body:{username:candidate.username}});
      closeDialog();
      await loadInvitations(0);
      feedback('邀请已发出。');
    },'primary',{disabled:true});
    const lookup = input('username','',{required:true,minlength:4,maxlength:32,autofocus:true,
      pattern:'[A-Za-z0-9_]{4,32}',on:{input:() => { candidate = null; send.disabled = true; preview.hidden = true; }}});
    dialog('邀请组员',form(async element => {
      candidate = null;
      send.disabled = true;
      preview.hidden = true;
      const searched = new FormData(element).get('username');
      const found = await api('/users/lookup',{query:{username:searched}});
      if (lookup.value !== searched) return;
      candidate = found;
      preview.textContent = candidate.nickname + ' · @' + candidate.username;
      preview.hidden = false;
      send.disabled = false;
    }, field('组员的准确账号',lookup), submit('查找账号','')),preview,actions(send));
  }

  async function loadMembers(page) {
    const result = await api('/projects/' + id + '/members',{query:{page}});
    list.replaceChildren(table(['成员','项目角色','加入时间','操作'],result.items.map(m =>
      [h('div',{},h('strong',{},m.nickname),h('small',{class:'block'},'@' + m.username)),
        pill(m.role === 'LEADER' ? '组长' : '组员',m.role === 'LEADER' ? 'course' : ''),formatTime(m.joinedAt),
        editable && m.role !== 'LEADER' ? actions(
          button('转让组长',() => confirmAction('转让组长','转让给 ' + m.nickname + ' 后，你将成为普通组员。','确认转让',async () => {
            await api('/projects/' + id + '/transfer',{method:'POST',body:{userId:m.id}});
            closeDialog(); await membersPage(); feedback('组长已转让。');
          }),'small-button'),
          button('移除',() => confirmAction('移除成员',m.nickname + ' 将失去项目访问权限，历史贡献仍会保留。','确认移除',async () => {
            await api('/projects/' + id + '/members/' + m.id,{method:'DELETE'});
            closeDialog(); await loadMembers(0); feedback('成员已移除。');
          }),'small-button danger-button')) : '—'])), ...(pager(result,loadMembers) ? [pager(result,loadMembers)] : []));
  }

  async function loadInvitations(page) {
    const result = await api('/projects/' + id + '/invitations',{query:{page}});
    invitations.replaceChildren(result.items.length
      ? table(['被邀请人','状态','发送时间','操作'],result.items.map(invite => [invite.inviteeName + ' · @' + invite.inviteeUsername,
        statePill(invite.status),formatTime(invite.createdAt),editable && invite.status === 'PENDING'
          ? click('撤销',async () => {
            await api('/projects/' + id + '/invitations/' + invite.id + '/revoke',{method:'POST'});
            await loadInvitations(page); feedback('邀请已撤销。');
          },'small-button') : '—'])) : empty('暂无发出的邀请'), ...(pager(result,loadInvitations) ? [pager(result,loadInvitations)] : []));
  }
}

export async function invitationsPage() {
  const list = h('div', {});
  const state = select('status',invitationStatuses,'','全部状态');
  content.replaceChildren(card(form(() => load(0), h('div',{class:'filters'},field('状态',state),submit('筛选',''))),list));
  async function load(page) {
    const result = await api('/invitations',{query:{status:state.value,page}});
    list.replaceChildren(result.items.length ? h('ul',{class:'invite-list'},result.items.map(i =>
      h('li',{},h('div',{},h('h3',{},i.projectName),h('p',{class:'muted'},i.course + ' · ' + i.inviterName + ' 邀请 · ' + formatTime(i.createdAt))),
        statePill(i.status), i.status === 'PENDING' && i.projectStatus === 'ACTIVE' && actions(
          click('接受邀请',async () => {
            await api('/invitations/' + i.id + '/accept',{method:'POST'});
            await load(page); feedback('已加入项目。');
          },'primary'),
          click('拒绝',async () => {
            await api('/invitations/' + i.id + '/decline',{method:'POST'});
            await load(page); feedback('已拒绝邀请。');
          })),
        i.status === 'ACCEPTED' && link('打开项目','/projects/' + i.projectId,'button')))) : empty('暂无符合条件的邀请'),
      ...(pager(result,load) ? [pager(result,load)] : []));
  }
  await load(0);
}

export async function collect(route, query = {}) {
  const first = await api(route,{query:{...query,page:0,size:100}});
  const items = [...first.items];
  for (let page = 1; page * 100 < first.total; page++) {
    items.push(...(await api(route,{query:{...query,page,size:100}})).items);
  }
  return items;
}

export async function filesPage() {
  const [tasks, members] = await Promise.all([collect('/projects/' + id + '/tasks'),api('/projects/' + id + '/attachments/uploaders')]);
  const taskChoices = Object.fromEntries(tasks.map(t => [t.id,t.title]));
  const memberChoices = Object.fromEntries(members.map(m => [m.id,m.nickname]));
  const types = {image:'图片',document:'文档',archive:'压缩包',video:'视频',audio:'音频',text:'文本与代码'};
  const filters = {};
  const list = h('div',{});
  content.replaceChildren(card(form(async element => {
    Object.assign(filters,Object.fromEntries(new FormData(element)));
    await load(0);
  },h('div',{class:'filters'},field('所属任务',select('taskId',taskChoices,'','全部任务')),field('上传人',select('uploaderId',memberChoices,'','全部上传人')),
    field('文件类型',select('type',types,'','全部类型')),submit('筛选',''))), list));
  async function load(page) {
    const result = await api('/projects/' + id + '/attachments',{query:{...filters,page}});
    list.replaceChildren(result.items.length ? table(['文件','所属任务','上传者','上传时间'],result.items.map(file =>
      [fileLinks([file]), file.taskId ? link(file.taskTitle,'/tasks/' + file.taskId) : '项目参考',
        file.uploaderName,formatTime(file.createdAt)])) : empty('暂无符合条件的文件'), ...(pager(result,load) ? [pager(result,load)] : []));
  }
  await load(0);
}
