const byId = id => document.getElementById(id);
const meta = name => document.querySelector('meta[name="' + name + '"]')?.content || '';
export const context = meta('unitask-context');
export const content = byId('content');
export const id = Number(document.body.dataset.entity);
const modal = byId('modal');

export const statusLabels = {
  UNASSIGNED: '待分配', IN_PROGRESS: '进行中', PENDING_REVIEW: '待审核', REWORK: '已打回',
  COMPLETED: '已完成', ACTIVE: '进行中', ARCHIVED: '已归档', PENDING: '待处理',
  ACCEPTED: '已接受', DECLINED: '已拒绝', REVOKED: '已撤销',
};
export const priorities = { HIGH: '高优先级', NORMAL: '普通', LOW: '低优先级' };

export function h(tag, props, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value == null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'on') Object.entries(value).forEach(([event, callback]) => node.addEventListener(event, callback));
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat(Infinity)) {
    if (child != null && child !== false) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

export function path(route, query) {
  const parameters = new URLSearchParams();
  for (const [key, val] of Object.entries(query || {})) {
    if (val !== '' && val != null) parameters.set(key, val);
  }
  return context + route + (parameters.size ? '?' + parameters : '');
}
export const link = (label, route, variant) => h('a', {href: path(route), class: variant}, label);
export const button = (label, onClick, variant, props) =>
  h('button', {type: 'button', class: variant, ...props, on: {click: onClick}}, label);
export const form = (handler, ...children) =>
  h('form', {class: 'field-grid', on: {submit: async event => {
    event.preventDefault();
    const element = event.currentTarget;
    const submit = event.submitter || element.querySelector('button[type=submit]');
    await act(submit, () => handler(element, event));
  }}}, children);
export const submit = (label, variant = 'primary') => h('button', {type: 'submit', class: variant}, label);
export const field = (label, input, note) => h('label', {}, label, input, note && h('small', {}, note));
export const input = (name, value, props) => h('input', {name, value: value ?? '', ...props});
export const textarea = (name, value, props) => h('textarea', {name, ...props}, value ?? '');
export function select(name, choices, value, empty, props) {
  return h('select', {name, ...props},
    empty != null && h('option', {value: '', selected: !value}, empty),
    Object.entries(choices).map(([key, label]) => h('option', {value: key, selected: String(value) === key}, label)));
}
export const card = (...children) => h('section', {class: 'card'}, children);
export const empty = label => h('p', {class: 'empty'}, label);
export const pill = (label, state = '') => h('span', {class: 'pill ' + state.toLowerCase().replaceAll('_', '-')}, label);
export const statePill = state => pill(statusLabels[state] || state, state);
export const row = (...children) => h('div', {class: 'row'}, children);
export const actions = (...children) => h('div', {class: 'actions'}, children);
export const sectionTitle = (title, extra) => h('div', {class: 'section-title'}, h('h2', {}, title), extra);

export function formatTime(value) {
  if (!value) return '未设置';
  return new Intl.DateTimeFormat('zh-CN', {month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    timeZone: 'Asia/Shanghai', hourCycle: 'h23'}).format(new Date(value));
}
export function dateValue(value) {
  if (!value) return '';
  return new Date(Date.parse(value) + 8 * 3600 * 1000).toISOString().slice(0, 16);
}
export const deadlineField = value => field('截止时间', input('deadline', dateValue(value), {type: 'datetime-local'}), '北京时间，可留空');
export const dateInput = value => value ? new Date(value + '+08:00').toISOString() : null;
export function fileSize(bytes) {
  return bytes >= 1048576 ? (bytes / 1048576).toFixed(1) + ' MiB'
    : bytes >= 1024 ? (bytes / 1024).toFixed(1) + ' KiB' : bytes + ' B';
}

export async function api(route, options = {}) {
  const {method = 'GET', body, files, query} = options;
  const headers = new Headers({Accept: 'application/json'});
  const config = {method, headers};
  if (method !== 'GET') headers.set(meta('unitask-csrf-header') || 'X-CSRF-TOKEN', meta('unitask-csrf'));
  if (files?.length) {
    const data = new FormData();
    data.append('data', new Blob([JSON.stringify(body || {})], {type: 'application/json'}));
    files.forEach(file => data.append('files', file));
    config.body = data;
  } else if (body !== undefined) {
    headers.set('Content-Type', 'application/json');
    config.body = JSON.stringify(body);
  }
  let response;
  try { response = await fetch(path('/api' + route, query), config); }
  catch { throw new Error('连接失败，请检查服务是否运行后重试。'); }
  if (response.status === 401) {
    window.location.assign(path('/login'));
    throw new Error('登录已过期。');
  }
  if (!response.ok) {
    let payload;
    try { payload = await response.json(); } catch { /* container response */ }
    const error = new Error(payload?.message || '操作失败（' + response.status + '）。');
    error.status = response.status;
    error.fields = payload?.fieldErrors || {};
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

export function feedback(message, type = 'success', scope = byId('feedback')) {
  scope.className = 'notice ' + type;
  scope.textContent = message;
  scope.hidden = false;
}
export async function act(control, task) {
  const original = control?.textContent;
  const scope = modal.open ? modal.querySelector('.dialog-feedback') : byId('feedback');
  if (scope) scope.hidden = true;
  if (control) {
    control.disabled = true;
    control.setAttribute('aria-busy','true');
    control.textContent = '处理中…';
  }
  try { await task(); }
  catch (error) {
    if (scope) feedback(error.message, 'danger', scope);
    console.warn(error.message);
  } finally {
    if (control) {
      control.disabled = false;
      control.removeAttribute('aria-busy');
      control.textContent = original;
    }
  }
}
export function click(label, task, variant, attrs) {
  return button(label, event => act(event.currentTarget, task), variant, attrs);
}

export function dialog(title, ...body) {
  const alreadyOpen = modal.open;
  const heading = h('h2', {id: 'dialog-title'}, title);
  const exit = button('关闭', () => modal.close(), 'dialog-close');
  const error = h('p', {class: 'dialog-feedback', role: 'alert', hidden: true});
  modal.replaceChildren(h('div', {class: 'dialog-top'}, heading, exit), error, ...body);
  if (!alreadyOpen) modal.showModal();
  return modal;
}
export const closeDialog = () => modal.close();

export function confirmAction(title, explanation, label, action) {
  dialog(title, h('p', {class: 'dialog-copy'}, explanation),
    actions(button('取消', closeDialog), click(label, action, 'danger-button')));
}

export function picker(label = '添加附件') {
  const files = [];
  const list = h('ul', {class: 'picked-files'});
  function show() {
    list.replaceChildren(...files.map((file, i) => h('li', {}, h('span', {}, file.name, ' · ', fileSize(file.size)),
      button('移除', () => { files.splice(i, 1); show(); }, 'small-button', {'aria-label':'移除 ' + file.name}))));
  }
  const node = h('input', {type: 'file', multiple: true, name: 'files', on: {change: event => {
    files.push(...event.currentTarget.files);
    event.currentTarget.value = '';
    show();
  }}});
  const maxFiles = Number(meta('unitask-upload-files'));
  const maxFile = Number(meta('unitask-upload-file-bytes'));
  const maxBatch = Number(meta('unitask-upload-batch-bytes'));
  const note = '每次最多 ' + maxFiles + ' 个文件，单文件 ' + fileSize(maxFile) + '，总计 ' + fileSize(maxBatch);
  return {files, view: h('div', {class: 'file-picker'}, field(label, node, note), list)};
}

export function fileLinks(files) {
  if (!files?.length) return null;
  return h('ul', {class:'file-links'}, files.map(file => h('li', {},
    h('span', {}, h('strong', {}, file.originalName), h('small', {}, fileSize(file.sizeBytes))),
    file.previewable && button('预览', () => preview(file), 'small-button'),
    h('a', {href:file.downloadUrl, class:'button small-button'}, '下载'))));
}
export function preview(file) {
  let viewer;
  if (file.mediaType.startsWith('image/')) viewer = h('img', {src:file.previewUrl, alt:file.originalName});
  else if (file.mediaType.startsWith('video/')) viewer = h('video', {src:file.previewUrl, controls:true, preload:'metadata'});
  else if (file.mediaType.startsWith('audio/')) viewer = h('audio', {src:file.previewUrl, controls:true, preload:'metadata'});
  else viewer = h('iframe', {src:file.previewUrl, title:file.originalName, sandbox:''});
  dialog(file.originalName, h('div', {class:'preview'}, viewer),
    actions(h('a', {href:file.downloadUrl, class:'button'}, '下载原文件')));
}

export function pager(result, go) {
  if (result.total <= result.size) return null;
  const pages = Math.ceil(result.total / result.size);
  return h('nav', {class:'pager', 'aria-label':'分页'},
    click('上一页', () => go(result.page - 1), '', {disabled:result.page === 0}),
    h('span', {}, '第 ' + (result.page + 1) + ' / ' + pages + ' 页 · ' + result.total + ' 条'),
    click('下一页', () => go(result.page + 1), '', {disabled:result.page + 1 >= pages}));
}

export async function refreshUnread() {
  try {
    const {count} = await api('/notifications/unread-count');
    const badge = byId('unread-count');
    badge.textContent = count > 99 ? '99+' : String(count);
    badge.hidden = count === 0;
  } catch { /* next polling interval retries */ }
}

export function table(headers, records) {
  return h('div', {class:'table-scroll'}, h('table', {},
    h('thead', {}, h('tr', {}, headers.map(title => h('th', {scope:'col'}, title)))),
    h('tbody', {}, records.map(cells => h('tr', {}, cells.map(cell => h('td', {}, cell)))))));
}
