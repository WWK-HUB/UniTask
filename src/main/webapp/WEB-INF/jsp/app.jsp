<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html><html lang="zh-CN">
<head>
  <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="unitask-context" content="<c:out value='${pageContext.request.contextPath}'/>">
  <meta name="unitask-csrf" content="<c:out value='${_csrf.token}'/>">
  <meta name="unitask-csrf-header" content="<c:out value='${_csrf.headerName}'/>">
  <meta name="unitask-upload-files" content="<c:out value='${uploadFiles}'/>">
  <meta name="unitask-upload-file-bytes" content="<c:out value='${uploadFileBytes}'/>">
  <meta name="unitask-upload-batch-bytes" content="<c:out value='${uploadBatchBytes}'/>">
  <title><c:out value="${heading}"/> · UniTask</title>
  <link rel="stylesheet" href="<c:url value='/css/app.css'/>">
  <script type="module" src="<c:url value='/js/app.js'/>"></script>
</head>
<body class="workspace" data-screen="<c:out value='${screen}'/>" data-entity="<c:out value='${entityId}'/>">
<a href="#main" class="skip">跳到主内容</a>
<aside class="sidebar" id="sidebar">
  <a href="<c:url value='/dashboard'/>" class="brand"><span class="brand-mark">U</span> UniTask</a>
  <p class="workspace-label">课程协作空间</p>
  <nav aria-label="主要导航">
    <a data-nav="dashboard" href="<c:url value='/dashboard'/>"><span class="nav-icon" aria-hidden="true">◫</span> 工作台</a>
    <a data-nav="projects" href="<c:url value='/projects'/>"><span class="nav-icon" aria-hidden="true">▣</span> 我的项目</a>
    <a data-nav="mine" href="<c:url value='/my/tasks'/>"><span class="nav-icon" aria-hidden="true">✓</span> 我的任务</a>
    <a data-nav="notifications" href="<c:url value='/notifications'/>"><span class="nav-icon" aria-hidden="true">◉</span> 消息中心</a>
    <a data-nav="invitations" href="<c:url value='/invitations'/>"><span class="nav-icon" aria-hidden="true">✉</span> 项目邀请</a>
    <a data-nav="settings" href="<c:url value='/settings'/>"><span class="nav-icon" aria-hidden="true">⚙</span> 账号设置</a>
  </nav>
  <div class="side-footer">
    <span class="avatar" aria-hidden="true">我</span><span class="person-name"><c:out value="${actorName}"/></span>
    <form action="<c:url value='/logout'/>" method="post">
      <input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>">
      <button type="submit" class="logout">退出</button>
    </form>
  </div>
</aside>
<div class="workspace-body">
<header class="topbar">
  <button class="nav-toggle" type="button" aria-controls="sidebar" aria-expanded="false">菜单</button>
  <c:choose><c:when test="${project != null}">
    <div class="crumb"><a href="<c:url value='/projects'/>">我的项目</a><span aria-hidden="true">/</span><a href="<c:url value='/projects/${project.id}'/>"><c:out value="${project.name}"/></a></div>
  </c:when><c:otherwise><span class="crumb"><c:out value="${heading}"/></span></c:otherwise></c:choose>
  <a class="notification-link" href="<c:url value='/notifications'/>">消息 <span class="unread-badge" id="unread-count" hidden>0</span></a>
</header>
<main id="main">
  <c:if test="${project != null}">
    <nav class="project-tabs" aria-label="项目导航">
      <a data-tab="project" href="<c:url value='/projects/${project.id}'/>">概览</a>
      <a data-tab="tasks" href="<c:url value='/projects/${project.id}/tasks'/>">任务</a>
      <a data-tab="members" href="<c:url value='/projects/${project.id}/members'/>">成员</a>
      <a data-tab="files" href="<c:url value='/projects/${project.id}/files'/>">文件</a>
    </nav>
  </c:if>
  <h1 class="page-title"><c:out value="${heading}"/></h1>
  <div id="feedback" role="status" aria-live="polite" hidden></div>
  <section id="content" aria-label="<c:out value='${heading}'/>"><p class="empty" role="status">正在加载…</p></section>
  <noscript><p class="notice danger">需要启用浏览器 JavaScript 才能操作 UniTask。</p></noscript>
</main>
</div>
<dialog id="modal" aria-labelledby="dialog-title"></dialog>
</body></html>
