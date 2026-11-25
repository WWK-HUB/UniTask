import {
  api,h,content,link,click,form,submit,field,input,select,card,empty,statePill,actions,feedback,path,pager,formatTime,refreshUnread
} from './ui.js';
import {dashboard,projectsPage,overview,membersPage,filesPage,invitationsPage} from './projects.js';
import {projectTasks,myTasks,detail} from './tasks.js';

const screen = document.body.dataset.screen;
const nav = ['project','tasks','members','files','task'].includes(screen) ? 'projects' : screen;
document.querySelector('[data-nav="' + nav + '"]')?.setAttribute('aria-current','page');
document.querySelector('[data-tab="' + (screen === 'task' ? 'tasks' : screen) + '"]')?.setAttribute('aria-current','page');

const toggle = document.querySelector('.nav-toggle');
toggle.addEventListener('click',() => {
  const open = toggle.getAttribute('aria-expanded') === 'true';
  toggle.setAttribute('aria-expanded',String(!open));
  document.body.classList.toggle('sidebar-open',!open);
});
document.addEventListener('keydown',event => {
  if (event.key === 'Escape' && document.body.classList.contains('sidebar-open')) {
    document.body.classList.remove('sidebar-open');
    toggle.setAttribute('aria-expanded','false');
    toggle.focus();
  }
});

const screens = {dashboard,projects:projectsPage,project:overview,members:membersPage,
  files:filesPage,invitations:invitationsPage,tasks:projectTasks,task:detail,mine:myTasks,notifications,settings};
async function render() {
  try { await screens[screen](); }
  catch (error) {
    content.replaceChildren(card(h('p',{class:'notice danger',role:'alert'},error.message),
      actions(click('重试',render))));
  }
}
render();
refreshUnread();
setInterval(refreshUnread,25000);

async function notifications() {
  const filter = select('unread',{true:'仅未读',false:'仅已读'},'','全部状态');
  const result = h('div',{});
  content.replaceChildren(card(h('div',{class:'toolbar'},
    form(() => load(0), h('div',{class:'filters'},field('消息状态',filter),submit('筛选',''))),
    click('全部标为已读',async () => {
      await api('/notifications/read-all',{method:'POST'});
      await load(0); await refreshUnread(); feedback('所有消息均已标为已读。');
    })),result));
  async function load(page) {
    const messages = await api('/notifications',{query:{unread:filter.value,page}});
    const nav = pager(messages,load);
    result.replaceChildren(messages.items.length ? h('ul',{class:'inbox-list'},messages.items.map(message => {
      const unread = !message.readAt;
      return h('li',{class:unread ? 'unread' : ''},
        h('div',{},h('div',{class:'row'},h('h3',{},h('a',{href:message.link},message.title)),
          unread && h('span',{class:'pill course'},'未读')),
          h('p',{class:'pre-line muted'},message.summary),h('small',{},formatTime(message.createdAt))),
        unread && click('标为已读',async () => {
          await api('/notifications/' + message.id + '/read',{method:'PATCH'});
          await load(page); await refreshUnread();
        },'small-button'));
    })) : empty('暂无符合条件的消息'), ...[nav].filter(Boolean));
  }
  await load(0);
}

async function settings() {
  const me = await api('/me');
  content.replaceChildren(h('div',{class:'settings-grid'},
    card(h('h2',{},'个人资料'), h('p',{class:'muted'},'账号 · @' + me.username),
      form(async element => {
        const renamed = await api('/me',{method:'PATCH',body:{nickname:new FormData(element).get('nickname')}});
        document.querySelector('.person-name').textContent = renamed.nickname;
        feedback('昵称已更新。');
      },field('昵称',input('nickname',me.nickname,{maxlength:40,required:true})),submit('保存昵称'))),
    card(h('h2',{},'修改密码'), h('p',{class:'muted'},'修改完成后需要重新登录。'),
      form(async element => {
        const values = new FormData(element);
        if (values.get('newPassword') !== values.get('verifyPassword')) throw new Error('两次输入的新密码不一致。');
        await api('/me/password',{method:'POST',body:{currentPassword:values.get('currentPassword'),newPassword:values.get('newPassword')}});
        location.assign(path('/login'));
      },field('当前密码',input('currentPassword','',{type:'password',required:true,autocomplete:'current-password'})),
      field('新密码',input('newPassword','',{type:'password',required:true,minlength:8,maxlength:64,autocomplete:'new-password'}),
        '8–64 个字符，UTF-8 最多 72 字节'),
      field('确认新密码',input('verifyPassword','',{type:'password',required:true,minlength:8,maxlength:64,autocomplete:'new-password'})),
      submit('更新密码')))));
}
