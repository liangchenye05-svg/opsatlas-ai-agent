<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import axios from 'axios'
import { ElAlert, ElButton, ElDialog, ElDrawer, ElOption, ElSelect, ElSwitch } from 'element-plus'
import { analyze, api, errorMessage, saveToken, token } from './api'
import type { Actor, Audit, Citation, Dashboard, Detail, Document, Report, Session, Step, Store } from './types'
import TrendPanel from './components/TrendPanel.vue'
import ExecutionTimeline from './components/ExecutionTimeline.vue'
import CitationList from './components/CitationList.vue'

const roles = { HEADQUARTERS: '总部运营', REGION: '区域经理', STORE: '门店店长' }
const areas = [{id:'overview',label:'经营总览'},{id:'agent',label:'分析工作台'},{id:'knowledge',label:'制度知识库'},{id:'reports',label:'周报与审计'}] as const
type Area = typeof areas[number]['id']
const actions: Record<string,string> = {
  analysis_started:'发起分析', draft_created:'生成草稿', analysis_failed:'分析失败', report_confirmed:'确认周报',
  mcp_metrics:'MCP 经营查询', authorized:'权限校验', get_store_metrics:'经营工具', search_company_policy:'制度工具', verified:'证据核对'
}
const states: Record<Session['state'],string> = {RUNNING:'执行中',DRAFT:'待确认',CONFIRMED:'已保存',FAILED:'失败'}
const anomalyNames = {NORMAL:'未触发退款异常',REFUND_RATE_RISE:'退款率明显上升',NO_DATA:'该区间暂无订单'}
const currency = new Intl.NumberFormat('zh-CN', {style:'currency',currency:'CNY'})
const defaultQuestion = '分析我负责门店上周的退款异常，与前一周对比，结合售后制度给出建议并生成周报草稿。'

const account = ref<Actor | null>(null)
const username = ref('xuhui')
const password = ref('')
const area = ref<Area>('overview')
const busy = ref(false)
const problem = ref('')
const toast = ref('')
const stores = ref<Store[]>([])
const storeId = ref<number>()
const from = ref('')
const to = ref('')
const regionId = ref<number>()
const dashboard = ref<Dashboard>()
const orders = ref<Detail[]>([])
const refunds = ref<Detail[]>([])
const question = ref(defaultQuestion)
const configured = ref(false)
const useModel = ref(false)
const active = ref<Session | null>(null)
const steps = ref<Step[]>([])
const analysisPending = ref(false)
const analysisProblem = ref('')
const sessions = ref<Session[]>([])
const reports = ref<Report[]>([])
const audits = ref<Audit[]>([])
const docs = ref<Document[]>([])
const search = ref('')
const hits = ref<Citation[]>([])
const searched = ref(false)
const selectedCitation = ref<Citation>()
const citationOpen = ref(false)
const permissionOpen = ref(false)
const reportPreviewOpen = ref(false)
const selectedReport = ref<Report>()
const uploadFile = ref<File>()
const uploadControl = ref<HTMLInputElement>()
const title = ref('')

const dateRange = computed(() => dashboard.value ? displayDate(dashboard.value.period.from) + ' 至 ' + displayDate(dashboard.value.period.to) : '')
const regionChoices = computed(() => Array.from(new Map(stores.value.map(store => [store.regionId,store.regionName]))))
const eligibleStores = computed(() => stores.value.filter(store => !regionId.value || store.regionId === regionId.value))

function fmt(value: number) { return currency.format(value) }
function sign(value: number | null, unit = '') {
  return value == null ? '—' : (value > 0 ? '+' : '') + value.toFixed(2) + unit
}
function displayDate(value: string | number[]) {
  return Array.isArray(value) ? value.map((part,index) => String(part).padStart(index===0 ? 4 : 2,'0')).join('-') : value
}

async function perform(work: () => Promise<void>) {
  if (busy.value) return
  problem.value = ''
  toast.value = ''
  busy.value = true
  try { await work() }
  catch (error) {
    const status = axios.isAxiosError(error) ? error.response?.status : (error as { status?: number } | undefined)?.status
    if (status === 401 && account.value) {
      logout()
      problem.value = '会话已失效，请重新登录'
    } else problem.value = errorMessage(error)
  } finally { busy.value = false }
}

async function loadDashboard() {
  const params = { storeId: storeId.value, regionId: regionId.value, from: from.value || undefined, to: to.value || undefined }
  const [summary, orderRows, refundRows] = await Promise.all([
    api.get<Dashboard>('/dashboard', {params}),
    api.get<Detail[]>('/orders', {params}),
    api.get<Detail[]>('/refunds', {params}),
  ])
  dashboard.value = summary.data
  orders.value = orderRows.data
  refunds.value = refundRows.data
  from.value ||= summary.data.period.from
  to.value ||= summary.data.period.to
}

async function bootstrap() {
  const [who, branches, status] = await Promise.all([
    api.get<Actor>('/me'), api.get<Store[]>('/stores'), api.get<{modelConfigured:boolean}>('/agent/status'),
  ])
  account.value = who.data
  stores.value = branches.data
  storeId.value = branches.data.length === 1 ? branches.data[0].id : undefined
  configured.value = status.data.modelConfigured
  useModel.value = status.data.modelConfigured
  await loadDashboard()
}

async function login() {
  await perform(async () => {
    const response = await api.post<{accessToken:string}>('/auth/login', {username:username.value,password:password.value})
    saveToken(response.data.accessToken)
    password.value = ''
    await bootstrap()
  })
}

function logout() {
  saveToken(null)
  account.value = null
  area.value = 'overview'
  stores.value = []
  storeId.value = undefined
  from.value = ''
  to.value = ''
  dashboard.value = undefined
  orders.value = []
  refunds.value = []
  regionId.value = undefined
  active.value = null
  question.value = defaultQuestion
  steps.value = []
  analysisProblem.value = ''
  sessions.value = []
  reports.value = []
  audits.value = []
  docs.value = []
  search.value = ''
  hits.value = []
  searched.value = false
  selectedCitation.value = undefined
  citationOpen.value = false
  permissionOpen.value = false
  reportPreviewOpen.value = false
  selectedReport.value = undefined
  uploadFile.value = undefined
  title.value = ''
  problem.value = ''
  toast.value = ''
}

onMounted(() => {
  if (token()) perform(async () => {
    try { await bootstrap() }
    catch (error) { logout(); throw error }
  })
})

async function openArea(next: Area) {
  area.value = next
  await perform(async () => {
    if (next === 'overview') await loadDashboard()
    if (next === 'knowledge') docs.value = (await api.get<Document[]>('/knowledge/documents')).data
    if (next === 'reports') {
      const [history, saved, events] = await Promise.all([
        api.get<Session[]>('/analysis/sessions'), api.get<Report[]>('/reports'), api.get<Audit[]>('/audit'),
      ])
      sessions.value = history.data
      reports.value = saved.data
      audits.value = events.data
    }
  })
}

function openSession(session: Session) {
  active.value = session
  steps.value = session.draft?.steps || []
  analysisProblem.value = session.failure || ''
  area.value = 'agent'
}

async function runAnalysis() {
  if (busy.value || !question.value.trim()) return
  await perform(async () => {
    active.value = null
    steps.value = []
    analysisProblem.value = ''
    analysisPending.value = true
    try {
      active.value = await analyze({question:question.value.trim(),storeId:storeId.value,local:!useModel.value}, step => steps.value.push(step))
    } catch (error) {
      analysisProblem.value = errorMessage(error)
      throw error
    } finally { analysisPending.value = false }
  })
}

async function confirm() {
  const current = active.value
  if (current?.state !== 'DRAFT') return
  await perform(async () => {
    await api.post('/reports/' + current.id + '/confirm', {confirmed:true})
    active.value = {...current,state:'CONFIRMED'}
    reportPreviewOpen.value = false
    toast.value = '周报已保存'
  })
}

async function showCitation(reference: Citation) {
  await perform(async () => {
    selectedCitation.value = (await api.get<Citation>('/knowledge/citations/' + reference.id)).data
    citationOpen.value = true
  })
}

async function searchDocs() {
  if (!search.value.trim()) return
  await perform(async () => {
    hits.value = (await api.get<{citations:Citation[]}>('/knowledge/search', {params:{q:search.value.trim()}})).data.citations
    searched.value = true
  })
}

async function upload() {
  const file = uploadFile.value
  if (!file) return
  await perform(async () => {
    const form = new FormData()
    form.append('file', file)
    form.append('title', title.value)
    await api.post('/knowledge/documents', form)
    docs.value = (await api.get<Document[]>('/knowledge/documents')).data
    toast.value = '制度文档已上传并建立索引'
    title.value = ''
    uploadFile.value = undefined
    if (uploadControl.value) uploadControl.value.value = ''
  })
}
</script>

<template>
  <main v-if="!account" class="login-shell">
    <section class="login-copy">
      <div class="brand"><span class="mark">O</span>OpsAtlas</div>
      <h1>门店经营分析</h1>
      <p>查看已授权门店的经营表现、售后制度和待确认周报。</p>
    </section>
    <form class="card login-card" @submit.prevent="login">
      <h2>登录</h2>
      <label for="account">用户名</label>
      <input id="account" v-model="username" autocomplete="username" required>
      <label for="password">密码</label>
      <input id="password" v-model="password" type="password" autocomplete="current-password" required>
      <ElAlert v-if="problem" :title="problem" type="error" :closable="false" show-icon />
      <ElButton type="primary" native-type="submit" :loading="busy">登录系统</ElButton>
    </form>
  </main>

  <div v-else class="app-shell">
    <header class="topbar">
      <div class="brand"><span class="mark">O</span>OpsAtlas</div>
      <nav aria-label="工作台">
        <button v-for="item in areas" :key="item.id" :aria-pressed="area===item.id" :disabled="busy" @click="openArea(item.id)">{{item.label}}</button>
      </nav>
      <div class="identity">
        <span>{{account.displayName}}<small>{{roles[account.role]}}</small></span>
        <button :disabled="busy" @click="permissionOpen=true">权限范围</button>
        <button :disabled="busy" @click="logout">退出</button>
      </div>
    </header>

    <main class="workspace">
      <ElAlert v-if="problem && !(area==='agent' && analysisProblem)" :title="problem" type="error" :closable="false" show-icon />
      <ElAlert v-if="toast" :title="toast" type="success" :closable="false" show-icon />

      <template v-if="area==='overview'">
        <div class="heading"><div><h1>经营总览</h1><p v-if="dateRange">{{dateRange}}</p></div></div>
        <form class="card filters" @submit.prevent="perform(loadDashboard)">
          <label>区域
            <ElSelect v-model="regionId" @change="storeId=undefined" clearable placeholder="全部授权区域" aria-label="区域">
              <ElOption v-for="[id,name] in regionChoices" :key="id" :label="name" :value="id"/>
            </ElSelect>
          </label>
          <label>门店
            <ElSelect v-model="storeId" clearable placeholder="全部授权门店" aria-label="门店">
              <ElOption v-for="store in eligibleStores" :key="store.id" :label="store.name" :value="store.id"/>
            </ElSelect>
          </label>
          <label>开始日期<input v-model="from" type="date"></label>
          <label>结束日期<input v-model="to" type="date"></label>
          <ElButton type="primary" native-type="submit" :loading="busy">查询指标</ElButton>
        </form>
        <section v-if="dashboard" class="metrics" aria-label="经营指标">
          <article class="card metric"><span>销售额</span><strong>{{fmt(dashboard.current.grossSales)}}</strong><small>环比 {{sign(dashboard.change.grossSalesPercent,'%')}}</small></article>
          <article class="card metric"><span>订单数</span><strong>{{dashboard.current.orders}}</strong><small>环比 {{sign(dashboard.change.ordersPercent,'%')}}</small></article>
          <article class="card metric refund"><span>退款率</span><strong>{{dashboard.current.refundRate.toFixed(2)}}%</strong><small>{{sign(dashboard.change.refundRatePoints,' 个百分点')}}</small></article>
          <article class="card metric"><span>退款金额</span><strong>{{fmt(dashboard.current.refundAmount)}}</strong><small>{{dashboard.current.refundedOrders}} 笔退款订单</small></article>
        </section>
        <p v-if="dashboard" class="comparison-caption">对照周期 {{displayDate(dashboard.comparison.from)}} 至 {{displayDate(dashboard.comparison.to)}}</p>
        <TrendPanel v-if="dashboard" :days="dashboard.trend" />
        <div class="columns">
          <section class="card"><h2>最近订单</h2><ul class="records"><li v-for="row in orders.slice(0,6)" :key="row.id"><span>{{row.id}} · {{row.orderedAt?.replace('T',' ')}}</span><strong>{{fmt(row.amount)}}</strong></li></ul><p v-if="!orders.length && !busy" class="empty-note">暂无记录。</p></section>
          <section class="card"><h2>最近退款</h2><ul class="records"><li v-for="row in refunds.slice(0,6)" :key="row.id"><span>{{row.reason}}</span><strong>{{fmt(row.amount)}}</strong></li></ul><p v-if="!refunds.length && !busy" class="empty-note">暂无记录。</p></section>
        </div>
      </template>

      <template v-else-if="area==='agent'">
        <div class="heading"><div><h1>分析工作台</h1><p>{{useModel ? '模型分析 · 使用已授权工具' : '证据预览 · 读取 MySQL 与企业制度'}}</p></div></div>
        <div class="agent-layout">
          <div class="agent-main">
            <form class="card analysis-form" @submit.prevent="runAnalysis">
              <label for="question">分析任务</label>
              <textarea id="question" v-model="question" :disabled="busy" maxlength="1200" rows="4" required></textarea>
              <div class="form-actions">
                <ElSelect v-model="storeId" :disabled="busy" clearable aria-label="分析门店" placeholder="全部授权门店">
                  <ElOption v-for="store in eligibleStores" :key="store.id" :label="store.name" :value="store.id"/>
                </ElSelect>
                <label v-if="configured" class="switch"><ElSwitch v-model="useModel" :disabled="busy"/> 使用已配置模型</label>
                <ElButton type="primary" native-type="submit" :loading="analysisPending" :disabled="busy || !question.trim()">{{useModel ? '开始模型分析' : '开始证据预览'}}</ElButton>
              </div>
            </form>
            <section v-if="active?.draft" class="card draft">
              <div class="section-title"><h2>经营周报草稿</h2><span class="pill" :class="{'pill-success':active.state==='CONFIRMED'}">{{states[active.state]}}</span></div>
              <p class="muted">{{displayDate(active.draft.metrics.period.from)}} 至 {{displayDate(active.draft.metrics.period.to)}}</p>
              <p class="finding" :class="{'finding-risk':active.draft.anomaly==='REFUND_RATE_RISE'}">{{anomalyNames[active.draft.anomaly]}}</p>
              <dl class="draft-metrics">
                <div><dt>销售额</dt><dd>{{fmt(active.draft.metrics.current.grossSales)}}</dd></div>
                <div><dt>订单数</dt><dd>{{active.draft.metrics.current.orders}}</dd></div>
                <div><dt>退款率</dt><dd>{{active.draft.metrics.current.refundRate.toFixed(2)}}%</dd></div>
              </dl>
              <p class="muted">前期退款率 {{active.draft.metrics.previous.refundRate.toFixed(2)}}% · 变化 {{sign(active.draft.metrics.change.refundRatePoints,' 个百分点')}}</p>
              <h3>处理建议</h3><p class="answer">{{active.draft.advice}}</p>
              <div v-if="active.state==='DRAFT'" class="form-actions draft-actions">
                <ElButton :disabled="busy" @click="reportPreviewOpen=true">预览周报</ElButton>
                <ElButton :disabled="busy" type="primary" @click="confirm">确认并保存周报</ElButton>
              </div>
            </section>
            <section v-else-if="analysisPending" class="card live-message" role="status"><h2>正在分析</h2><p class="muted">经营证据与结论会在任务完成后展示。</p></section>
          </div>
          <aside class="agent-evidence">
            <ExecutionTimeline :steps="steps" :pending="analysisPending" :failure="analysisProblem" :draft="active?.draft" />
            <section v-if="active?.draft" class="card">
              <h2>制度依据</h2>
              <p v-if="!active.draft.citations.length" class="empty-note">没有可引用的制度。</p>
              <CitationList :items="active.draft.citations" @open="showCitation" />
            </section>
          </aside>
        </div>
      </template>

      <template v-else-if="area==='knowledge'">
        <div class="heading"><div><h1>制度知识库</h1><p>{{docs.length}} 份文档</p></div></div>
        <div class="columns">
          <section class="card">
            <h2>制度检索</h2>
            <form class="inline" @submit.prevent="searchDocs"><input v-model="search" aria-label="制度检索词" maxlength="400" placeholder="例如退款制度" required><ElButton type="primary" native-type="submit" :loading="busy">检索</ElButton></form>
            <p v-if="searched && !hits.length" class="empty-note">没有找到相关制度。</p>
            <CitationList :items="hits" @open="showCitation" />
          </section>
          <section class="card">
            <h2>文档清单</h2>
            <p v-if="!docs.length && !busy" class="empty-note">暂无制度文档。</p>
            <ul class="records document-records"><li v-for="document in docs" :key="document.id"><strong>{{document.title}}</strong><small>{{document.source}}</small></li></ul>
            <form v-if="account.role==='HEADQUARTERS'" class="upload" @submit.prevent="upload">
              <label for="doc-title">上传制度</label><input id="doc-title" v-model="title" maxlength="140" placeholder="文档标题" required>
              <input ref="uploadControl" aria-label="制度文件" type="file" accept=".txt,.md" required @change="uploadFile=($event.target as HTMLInputElement).files?.[0]">
              <p class="muted">UTF-8 TXT 或 MD，最大 256 KB</p>
              <ElButton type="primary" native-type="submit" :loading="busy">上传并索引</ElButton>
            </form>
          </section>
        </div>
      </template>

      <template v-else>
        <div class="heading"><div><h1>周报与审计</h1><p>当前账号的分析与操作记录</p></div></div>
        <div class="columns">
          <section class="card">
            <h2>已保存周报</h2>
            <ul class="records"><li v-for="report in reports" :key="report.id"><button class="link" @click="selectedReport=report">{{displayDate(report.content.metrics.period.from)}} 至 {{displayDate(report.content.metrics.period.to)}}</button><small>{{anomalyNames[report.content.anomaly]}}</small></li></ul>
            <p v-if="!reports.length && !busy" class="empty-note">暂无周报。</p>
          </section>
          <section class="card">
            <h2>分析历史</h2>
            <ul class="records"><li v-for="item in sessions" :key="item.id"><button class="link" @click="openSession(item)">{{item.question}}</button><span class="pill">{{states[item.state]}}</span></li></ul>
            <p v-if="!sessions.length && !busy" class="empty-note">暂无分析记录。</p>
          </section>
        </div>
        <section class="card">
          <h2>操作审计</h2>
          <ul class="records"><li v-for="event in audits" :key="event.id"><strong>{{actions[event.actionCode]||event.actionCode}}</strong><small>{{event.createdAt.replace('T',' ')}} · {{event.outcome}}</small></li></ul>
          <p v-if="!audits.length && !busy" class="empty-note">暂无审计记录。</p>
        </section>
      </template>
    </main>

    <ElDrawer v-model="permissionOpen" title="当前账号权限" size="min(460px, 92vw)">
      <p><strong>{{account.displayName}}</strong> · {{roles[account.role]}}</p>
      <h2 class="drawer-heading">授权门店</h2>
      <ul class="records"><li v-for="store in stores" :key="store.id">{{store.name}}<small>{{store.regionName}}</small></li></ul>
    </ElDrawer>
    <ElDialog v-model="reportPreviewOpen" title="预览经营周报" width="min(680px, 92vw)">
      <template v-if="active?.draft">
        <p><strong>{{anomalyNames[active.draft.anomaly]}}</strong></p>
        <p>销售额 {{fmt(active.draft.metrics.current.grossSales)}} · 退款率 {{active.draft.metrics.current.refundRate.toFixed(2)}}%</p>
        <p class="answer">{{active.draft.advice}}</p>
        <p class="muted">随周报保存 {{active.draft.citations.length}} 条制度引用。</p>
      </template>
      <template #footer><ElButton @click="reportPreviewOpen=false">返回草稿</ElButton><ElButton type="primary" :loading="busy" @click="confirm">确认并保存周报</ElButton></template>
    </ElDialog>
    <ElDialog :model-value="!!selectedReport" @update:model-value="open => { if (!open) selectedReport = undefined }" title="已保存周报" width="min(680px, 92vw)">
      <template v-if="selectedReport">
        <p class="muted">{{displayDate(selectedReport.content.metrics.period.from)}} 至 {{displayDate(selectedReport.content.metrics.period.to)}}</p>
        <p><strong>{{anomalyNames[selectedReport.content.anomaly]}}</strong></p>
        <p>销售额 {{fmt(selectedReport.content.metrics.current.grossSales)}} · 退款率 {{selectedReport.content.metrics.current.refundRate.toFixed(2)}}%</p>
        <p class="answer">{{selectedReport.content.advice}}</p>
      </template>
    </ElDialog>
    <ElDialog v-model="citationOpen" :title="selectedCitation?.title || '制度片段'" width="min(650px, 90vw)">
      <p class="muted" v-if="selectedCitation">{{selectedCitation.source}} · 第 {{selectedCitation.ordinal}} 段</p>
      <p class="answer">{{selectedCitation?.content}}</p>
    </ElDialog>
  </div>
</template>
