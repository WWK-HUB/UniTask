<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html><html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>登录 · UniTask</title><link rel="stylesheet" href="<c:url value='/css/app.css'/>"></head>
<body class="auth">
<main class="auth-shell">
  <div class="auth-art"><a href="<c:url value='/login'/>" class="brand"><span class="brand-mark">U</span> UniTask</a>
    <h2>把课程项目<br>一步步做完。</h2><p>管理分工、同步成果，在同一个工作台完成验收。</p>
  </div>
  <section class="auth-form"><h1>登录</h1><p class="muted">使用 UniTask 账号继续。</p>
    <c:if test="${param.error != null}"><p class="notice danger" role="alert">账号或密码不正确。</p></c:if>
    <c:if test="${param.limited != null}"><p class="notice danger" role="alert">登录尝试过于频繁，请稍后重试。</p></c:if>
    <c:if test="${param.registered != null}"><p class="notice success" role="status">账号已注册，请登录。</p></c:if>
    <c:if test="${param.logout != null}"><p class="notice" role="status">你已退出登录。</p></c:if>
    <form method="post" action="<c:url value='/login'/>">
      <input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>">
      <label>账号<input name="username" required minlength="4" maxlength="32" autocomplete="username" autofocus></label>
      <label>密码<input name="password" required type="password" autocomplete="current-password"></label>
      <button type="submit" class="primary">登录</button>
    </form>
    <p class="form-footer">还没有账号？ <a href="<c:url value='/register'/>">创建账号</a></p>
  </section>
</main></body></html>
