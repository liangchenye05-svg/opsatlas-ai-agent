
const assert = require('node:assert/strict')
const { mkdir, writeFile } = require('node:fs/promises')
const path = require('node:path')
const { chromium } = require('../../frontend/node_modules/playwright')

const base = 'http://127.0.0.1:8192'
const desktop = { width: 1440, height: 960 }
const mobile = { width: 390, height: 844 }
const password = process.env.OPSATLAS_DEMO_PASSWORD
const mode = process.argv.includes('--evidence') ? 'evidence' : 'model'

async function photo(page, type, file) {
  const directory = path.join(__dirname, 'screenshots', type)
  await mkdir(directory, { recursive: true })
  await page.evaluate(() => document.fonts.ready)
  await page.screenshot({ path: path.join(directory, file + '.jpg'), type: 'jpeg', quality: 92, animations: 'disabled' })
}

async function run() {
  assert(password, 'Load the isolated OpsAtlas showcase demo password.')
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  const issues = []
  try {
    const page = await browser.newPage({ viewport: desktop, locale: 'zh-CN' })
    page.on('pageerror', error => issues.push(error.message))
    page.on('console', message => { if (message.type() === 'error') issues.push(message.text()) })
    await page.goto(base)
    await page.getByLabel('用户名').fill('xuhui')
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录系统' }).click()
    const cards = page.getByRole('region', { name: '经营指标' })
    await cards.getByText('16.67%', { exact: true }).waitFor()
    await cards.getByText('7 笔退款订单', { exact: true }).waitFor()
    await photo(page, 'desktop', '01-overview')
    await page.getByRole('button', { name: '退款率', exact: true }).click()
    await photo(page, 'desktop', '02-trend')

    await page.getByRole('button', { name: '分析工作台' }).click()
    await page.getByLabel('分析任务').fill('分析徐汇门店上周退款异常情况，与前一周比较，结合企业售后制度生成经营周报。')
    if (mode === 'evidence' && await page.getByRole('switch', { checked: true }).count()) {
      await page.locator('.el-switch__core').click()
    }
    await page.evaluate(() => {
      const original = window.fetch.bind(window)
      window.fetch = async (resource, options) => {
        const response = await original(resource, options)
        if (String(resource).endsWith('/api/agent/stream')) {
          window.showcaseStream = response.clone().text()
          window.fetch = original
        }
        return response
      }
    })
    await page.getByRole('button', { name: mode === 'model' ? '开始模型分析' : '开始证据预览' }).click({ timeout: 12000 })
    await page.waitForFunction(() => Boolean(window.showcaseStream), null, { timeout: 55000 })
    const raw = await page.evaluate(() => window.showcaseStream)
    const frames = raw.trim().split(/\r?\n\r?\n/)
    const failure = frames.find(frame => frame.startsWith('event:failure'))
    assert(!failure, 'Received a failure SSE frame')
    const done = frames.find(frame => frame.startsWith('event:done'))
    assert(done, 'The SSE stream did not send a completion frame.')
    const result = JSON.parse(done.split(/\r?\n/).find(line => line.startsWith('data:')).slice(5))
    const { draft } = result
    assert.equal(draft.mode, mode)
    assert.deepEqual(draft.metrics.storeIds, [1])
    assert.equal(draft.metrics.current.orders, 42)
    assert.equal(draft.metrics.current.refundedOrders, 7)
    const steps = draft.steps.map(step => step.stage)
    assert(steps.includes('get_store_metrics') && steps.includes('search_company_policy') && steps.includes('verified'))
    assert(draft.citations.length > 0)
    await page.getByRole('heading', { name: '经营周报草稿' }).waitFor()
    await photo(page, 'desktop', '03-agent')
    await page.locator('summary').filter({ hasText: '经营数据查询' }).click()
    await page.locator('summary').filter({ hasText: '企业制度检索' }).click()
    await page.locator('.step-values').getByText('42', { exact: true }).waitFor()
    await photo(page, 'desktop', '04-tools')

    await page.locator('.citation-list button').first().click()
    const citation = page.getByRole('dialog')
    await citation.getByText(draft.citations[0].content, { exact: true }).waitFor()
    await photo(page, 'desktop', '05-citation')
    await page.keyboard.press('Escape')
    await citation.waitFor({ state: 'hidden' })

    await page.getByRole('button', { name: '预览周报' }).click()
    const review = page.getByRole('dialog', { name: '预览经营周报' })
    await review.waitFor()
    const savedBeforeConfirm = await page.evaluate(async id => {
      const response = await fetch('/api/reports', { headers: { Authorization: 'Bearer ' + sessionStorage.getItem('opsatlas-token') } })
      if (!response.ok) throw new Error('Could not verify pre-confirmation state.')
      return (await response.json()).some(report => report.analysisId === id)
    }, result.id)
    assert.equal(savedBeforeConfirm, false)
    await photo(page, 'desktop', '06-report-confirmation')
    await review.getByRole('button', { name: '确认并保存周报' }).click()
    await page.getByText('周报已保存', { exact: true }).waitFor()
    await page.getByRole('button', { name: '周报与审计' }).click()
    await page.getByRole('heading', { name: '已保存周报' }).waitFor()
    await page.getByText('确认周报', { exact: true }).first().waitFor()
    await photo(page, 'desktop', '08-audit')
    await page.getByRole('button', { name: '制度知识库' }).click()
    await page.getByRole('heading', { name: '文档清单' }).waitFor()
    await page.getByText('门店经营周报规范', { exact: true }).first().waitFor()
    await photo(page, 'desktop', '07-knowledge')

    await page.setViewportSize(mobile)
    await page.getByRole('button', { name: '分析工作台' }).click()
    await page.getByRole('heading', { name: '分析工作台' }).waitFor()
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    await photo(page, 'mobile', '01-agent')
    assert.deepEqual(issues, [])
    const evidence = {
      browser: browser.version(), source: base, desktop, mobile, mode, steps,
      orders: draft.metrics.current.orders, refunds: draft.metrics.current.refundedOrders,
      refundRate: draft.metrics.current.refundRate, previousRefundRate: draft.metrics.previous.refundRate,
      citations: draft.citations.length, citationTextVerified: true, manualConfirmation: true,
      auditVisible: true, consoleErrors: 0, screenshots: 9
    }
    const out = path.join(__dirname, 'local-evidence')
    await mkdir(out, { recursive: true })
    await writeFile(path.join(out, 'browser.json'), JSON.stringify(evidence, null, 2) + '\n')
    console.log(JSON.stringify(evidence))
  } finally {
    await browser.close()
  }
}

run().catch(error => { console.error(error.stack); process.exitCode = 1 })
