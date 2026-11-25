<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html><html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>创建账号 · UniTask</title><link rel="stylesheet" href="<c:url value='/css/app.css'/>"></head>
<body class="auth">
<main class="auth-shell">
  <div class="auth-art"><a href="<c:url value='/login'/>" class="brand"><span class="brand-mark">U</span> UniTask</a>
    <h2>把课程项目<br>一步步做完。</h2><p>管理分工、同步成果，在同一个工作台完成验收。</p>
  </div>
  <section class="auth-form"><h1>创建账号</h1><p class="muted">选择一个组员可以用来邀请你的账号。</p>
    <c:if test="${error != null}"><p class="notice danger" role="alert"><c:out value="${error}"/></p></c:if>
    <form method="post" action="<c:url value='/register'/>">
      <input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>">
      <label>账号<input name="username" required minlength="4" maxlength="32" pattern="[A-Za-z0-9_]{4,32}" autocomplete="username" value="<c:out value='${username}'/>"></label>
      <small>4–32 位字母、数字或下划线</small>
      <label>昵称<input name="nickname" required maxlength="40" autocomplete="nickname" value="<c:out value='${nickname}'/>"></label>
      <label>密码<input name="password" required type="password" minlength="8" maxlength="64" autocomplete="new-password"></label>
      <small>8–64 个字符，UTF-8 最多 72 字节</small>
      <c:if test="${registrationCodeRequired}">
        <label>注册口令<input name="registrationCode" required type="password" maxlength="128" autocomplete="off"></label>
        <small>请向分享链接的人获取口令。</small>
      </c:if>
      <button type="submit" class="primary">创建账号</button>
    </form>
    <p class="form-footer">已有账号？ <a href="<c:url value='/login'/>">返回登录</a></p>
  </section>
</main></body></html>
