<template>
  <main class="login-page">
    <section class="campus-panel" aria-label="平台介绍">
      <div class="campus-grid" />
      <div class="brand-lockup">
        <div><strong>智餐外卖</strong><small>校园餐饮管理平台</small></div>
      </div>

      <div class="campus-copy">
        <span class="internal-badge">校园餐饮 · 智慧服务</span>
        <h1>让校园每一餐<br />更准时、更安心</h1>
        <p>杭州电子科技大学校园餐饮管理平台</p>
        <div class="feature-row">
          <span><i class="el-icon-data-analysis" /> 实时运营</span>
          <span><i class="el-icon-bell" /> 订单协同</span>
          <span><i class="el-icon-circle-check" /> 食安可控</span>
        </div>
      </div>

      <div class="campus-footer">
        <span>杭州电子科技大学校园餐饮平台</span>
        <span>校园餐饮数字化服务</span>
      </div>
    </section>

    <section class="login-panel">
      <div class="login-form-wrap">
        <div class="mobile-brand"><strong>智餐外卖</strong><span>校园餐饮管理平台</span></div>
        <div class="login-heading">
          <span class="eyebrow">WELCOME BACK</span>
          <h2>欢迎登录</h2>
          <p>使用校园餐饮运营账号进入管理后台</p>
        </div>

        <el-form ref="loginForm" :model="loginForm" :rules="loginRules" label-position="top">
          <el-form-item prop="username" label="账号">
            <el-input
              v-model="loginForm.username"
              type="text"
              auto-complete="off"
              placeholder="请输入工号或管理账号"
              prefix-icon="iconfont icon-user"
            />
          </el-form-item>
          <el-form-item prop="password" label="密码">
            <el-input
              v-model="loginForm.password"
              type="password"
              placeholder="请输入登录密码"
              prefix-icon="iconfont icon-lock"
              show-password
              @keyup.enter.native="handleLogin"
            />
          </el-form-item>
          <el-form-item class="login-action">
            <el-button
              :loading="loading"
              class="login-btn"
              type="primary"
              @click.native.prevent="handleLogin"
            >
              <span v-if="!loading">进入智餐平台</span>
              <span v-else>正在验证...</span>
            </el-button>
          </el-form-item>
        </el-form>

        <div class="security-tip">
          <i class="el-icon-lock" />
          <span>本系统仅供校内授权人员使用，请妥善保管账号信息</span>
        </div>
      </div>
      <p class="copyright">© 2026 智餐外卖 · 校园餐饮服务平台</p>
    </section>
  </main>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-property-decorator'
import { Form as ElForm } from 'element-ui'
import { UserModule } from '@/store/modules/user'

@Component({ name: 'Login' })
export default class extends Vue {
  private validateUsername = (_rule: any, value: string, callback: Function) => {
    value ? callback() : callback(new Error('请输入用户名'))
  }

  private validatePassword = (_rule: any, value: string, callback: Function) => {
    value && value.length >= 6
      ? callback()
      : callback(new Error('密码至少需要 6 位'))
  }

  private loginForm = {
    username: 'admin',
    password: '123456',
  } as { username: String; password: String }

  private loginRules = {
    username: [{ validator: this.validateUsername, trigger: 'blur' }],
    password: [{ validator: this.validatePassword, trigger: 'blur' }],
  }

  private loading = false

  private handleLogin() {
    ;(this.$refs.loginForm as ElForm).validate(async (valid: boolean) => {
      if (!valid) return false
      this.loading = true
      await UserModule.Login(this.loginForm as any)
        .then((res: any) => {
          if (String(res.code) === '1') {
            this.$router.push('/')
          } else {
            this.loading = false
          }
        })
        .catch(() => {
          this.loading = false
        })
    })
  }
}
</script>

<style lang="scss">
.login-page {
  min-height: 100%;
  display: grid;
  grid-template-columns: minmax(520px, 1.15fr) minmax(440px, 0.85fr);
  background: #f7faff;
  color: #172033;
}

.campus-panel {
  position: relative;
  overflow: hidden;
  min-height: 720px;
  padding: 48px 64px;
  display: flex;
  flex-direction: column;
  background: radial-gradient(circle at 74% 28%, rgba(79, 172, 254, 0.28), transparent 30%), linear-gradient(145deg, #071d3b 0%, #0b4f9f 58%, #1677dd 100%);
  color: #fff;
}

.campus-panel::before,
.campus-panel::after { content: ''; position: absolute; border: 1px solid rgba(255, 255, 255, 0.16); border-radius: 50%; }
.campus-panel::before { width: 520px; height: 520px; right: -180px; bottom: -160px; }
.campus-panel::after { width: 340px; height: 340px; right: -70px; bottom: -70px; }
.campus-grid { position: absolute; inset: 0; opacity: 0.13; background-image: linear-gradient(rgba(255,255,255,.4) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.4) 1px, transparent 1px); background-size: 48px 48px; mask-image: linear-gradient(to bottom right, #000, transparent 78%); }
.brand-lockup, .campus-copy, .campus-footer { position: relative; z-index: 1; }
.brand-lockup { display: flex; align-items: center; gap: 14px; }
.brand-lockup strong { display: block; font-size: 24px; letter-spacing: 2px; }
.brand-lockup small { display: block; margin-top: 3px; font-size: 11px; letter-spacing: 2.6px; color: #b9d8ff; }
.campus-copy { margin: auto 0; max-width: 620px; }
.internal-badge { display: inline-flex; padding: 7px 12px; border: 1px solid rgba(255,255,255,.25); border-radius: 999px; background: rgba(255,255,255,.1); color: #d7eaff; font-size: 12px; letter-spacing: 1px; }
.campus-copy h1 { margin: 26px 0 18px; font-size: clamp(42px, 4.1vw, 66px); line-height: 1.18; letter-spacing: 1px; }
.campus-copy p { margin: 0; font-size: 18px; color: #c9e1ff; letter-spacing: 3px; }
.feature-row { display: flex; gap: 28px; margin-top: 40px; color: #e7f2ff; font-size: 14px; }
.feature-row i { margin-right: 7px; color: #7dc1ff; }
.campus-footer { display: flex; justify-content: space-between; color: #a9cef7; font-size: 11px; letter-spacing: 1.4px; }
.login-panel { position: relative; display: grid; place-items: center; padding: 56px; background: #f8fbff; }
.login-form-wrap { width: min(100%, 400px); }
.mobile-brand { display: none; }
.login-heading .eyebrow { color: #1468d4; font-size: 12px; font-weight: 700; letter-spacing: 2px; }
.login-heading h2 { margin: 12px 0 8px; font-size: 34px; color: #10213a; }
.login-heading p { margin: 0 0 36px; color: #74839a; font-size: 15px; }
.login-page .el-form-item { margin-bottom: 24px; }
.login-page .el-form-item__label { padding-bottom: 8px; color: #35445d; font-size: 14px; font-weight: 600; line-height: 20px; }
.login-page .el-input__inner { height: 50px; padding-left: 44px; border: 1px solid #dce6f2; border-radius: 10px; background: #fff; color: #172033; font-size: 15px; transition: .2s ease; }
.login-page .el-input__inner:focus { border-color: #1468d4; box-shadow: 0 0 0 3px rgba(20,104,212,.1); }
.login-page .el-input__prefix { left: 14px; display: flex; align-items: center; color: #6b7c93; }
.login-action { margin-top: 34px; }
.login-btn { width: 100%; height: 50px; border: 0; border-radius: 10px; background: linear-gradient(135deg, #1468d4, #2086eb); box-shadow: 0 10px 24px rgba(20,104,212,.22); color: #fff; font-size: 15px; font-weight: 600; letter-spacing: 1px; }
.login-btn:hover, .login-btn:focus { background: linear-gradient(135deg, #0f59b8, #1676d7); color: #fff; }
.security-tip { display: flex; align-items: flex-start; gap: 8px; margin-top: 22px; padding: 13px 15px; border-radius: 9px; background: #edf5ff; color: #657792; font-size: 12px; line-height: 1.6; }
.security-tip i { margin-top: 3px; color: #1468d4; }
.copyright { position: absolute; bottom: 28px; margin: 0; color: #9aa8ba; font-size: 12px; }

@media (max-width: 960px) {
  .login-page { grid-template-columns: 1fr; }
  .campus-panel { display: none; }
  .login-panel { min-height: 100vh; padding: 36px 24px 80px; }
  .mobile-brand { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; margin-bottom: 56px; color: #12355f; font-size: 20px; }
  .mobile-brand span { color: #75869b; font-size: 12px; font-weight: 400; }
}
</style>
