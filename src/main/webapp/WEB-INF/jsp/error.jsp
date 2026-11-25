<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>页面提示 · UniTask</title><link rel="stylesheet" href="<c:url value='/css/app.css'/>"></head>
<body class="auth"><main class="error-card"><a href="<c:url value='/dashboard'/>" class="brand"><span class="brand-mark">U</span> UniTask</a>
<h1><c:out value="${status}" default="操作未完成"/></h1><p><c:out value="${message}" default="请刷新页面后重试。"/></p>
<a class="button primary" href="<c:url value='/dashboard'/>">返回工作台</a></main></body></html>
