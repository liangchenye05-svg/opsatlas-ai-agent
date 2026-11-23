const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { chromium } = require('../../frontend/node_modules/playwright')

const target = process.argv[2] || 'http://127.0.0.1:8092'
const name = process.argv[3] || 'phase-5'
const evidence = path.resolve(__dirname, '../../docs/evidence', name)
const timeout = 65000

async function login(page, username) {
  await page.goto(target)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(process.env.OPSATLAS_DEMO_PASSWORD)
  await page.getByRole('button', {name:'登录系统'}).click()
  await page.getByRole('heading', {name:'经营总览'}).waitFor()
}

async function run() {
  assert(process.env.OPSATLAS_DEMO_PASSWORD)
  fs.mkdirSync(evidence, {recursive:true})
  const browser = await chromium.launch({channel:'msedge',headless:true})
  const issues = []
  const desktop = await browser.newContext({viewport:{width:1440,height:960},locale:'zh-CN'})
  const page = await desktop.newPage()
  page.on('pageerror', error => issues.push(error.message))
  try {
    await login(page,'xuhui')
    await page.getByRole('region',{name:'经营指标'}).getByText('16.67%',{exact:true}).waitFor()
    await page.getByText('7 笔退款订单',{exact:true}).waitFor()
    await page.screenshot({path:path.join(evidence,'desktop.png'),fullPage:true})
    const denied = await page.evaluate(async () => {
      const response = await fetch('/api/dashboard?storeId=3',{headers:{
        Authorization:'Bearer '+sessionStorage.getItem('opsatlas-token'),
      }})
      return response.status
    })
    assert.equal(denied,403)

    await page.getByRole('button',{name:'分析工作台'}).click()
    const model = await page.getByRole('button',{name:'开始模型分析'}).count() === 1
    await page.evaluate(() => {
      const fetch = window.fetch
      window.fetch = async (...args) => {
        const response = await fetch.apply(window,args)
        if (new URL(response.url).pathname === '/api/agent/stream') {
          window.opsatlasTestStream = response.clone().text()
          window.fetch = fetch
        }
        return response
      }
    })
    await page.getByRole('button',{name:model?'开始模型分析':'开始证据预览'}).click()
    await page.waitForFunction(() => Boolean(window.opsatlasTestStream), null, {timeout})
    const events = await page.evaluate(() => window.opsatlasTestStream)
    const failure = events.split(/\r?\n\r?\n/).find(item=>item.startsWith('event:failure'))
    if (failure) {
      const line = failure.split(/\r?\n/).find(item=>item.startsWith('data:'))
      throw new Error('Agent SSE failure (' + JSON.parse(line.slice(5)).status + ')')
    }
    assert(events.includes('event:step') && events.includes('event:done'))
    await page.getByRole('heading',{name:'经营周报草稿'}).waitFor({timeout})
    const frame = JSON.parse(events.split(/\r?\n/).filter(line=>line.startsWith('data:')).at(-1).slice(5))
    assert.equal(frame.state,'DRAFT')
    assert.equal(frame.draft.mode, model?'model':'evidence')
    assert.equal(frame.draft.metrics.current.orders,42)
    assert.deepEqual(frame.draft.metrics.storeIds,[1])
    assert.equal(frame.draft.anomaly,'REFUND_RATE_RISE')
    assert(frame.draft.steps.some(item=>item.stage==='get_store_metrics'))
    assert(frame.draft.steps.some(item=>item.stage==='search_company_policy'))
    assert(frame.draft.citations.length>0)
    await page.locator('.citation-list button').first().click()
    await page.getByRole('dialog').waitFor()
    await page.getByRole('dialog').getByText(frame.draft.citations[0].content,{exact:true}).waitFor()
    await page.screenshot({path:path.join(evidence,'citation.png'),fullPage:true})
    await page.locator('.el-dialog__headerbtn').click()
    await page.getByRole('dialog').waitFor({state:'hidden'})
    await page.getByRole('button',{name:'确认并保存周报'}).click()
    await page.getByText('周报已保存',{exact:true}).waitFor()
    await page.screenshot({path:path.join(evidence,'draft.png'),fullPage:true})
    await page.getByRole('button',{name:'周报与审计'}).click()
    await page.getByRole('heading',{name:'已保存周报'}).waitFor()
    await page.getByText('确认周报',{exact:true}).first().waitFor()
    const saved = await page.evaluate(async id => {
      const response=await fetch('/api/reports',{headers:{Authorization:'Bearer '+sessionStorage.getItem('opsatlas-token')}})
      if (!response.ok) throw new Error('Could not verify saved report')
      return (await response.json()).find(item=>item.analysisId===id)?.id
    },frame.id)
    assert(saved)
    await page.getByRole('button',{name:'制度知识库'}).click()
    await page.getByRole('textbox',{name:'制度检索词'}).fill('火星天气和大气组成')
    await page.getByRole('button',{name:'检索',exact:true}).click()
    await page.getByText('没有找到相关制度。',{exact:true}).waitFor()

    const small = await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,locale:'zh-CN'})
    const mobile = await small.newPage()
    mobile.on('pageerror', error => issues.push(error.message))
    await login(mobile,'hq')
    await mobile.getByRole('region',{name:'经营指标'}).waitFor()
    assert(await mobile.evaluate(()=>document.documentElement.scrollWidth===innerWidth))
    await mobile.screenshot({path:path.join(evidence,'mobile.png'),fullPage:true})
    await mobile.getByRole('button',{name:'制度知识库'}).click()
    await mobile.getByLabel('上传制度').fill('OpsAtlas 浏览器验收制度')
    await mobile.getByLabel('制度文件').setInputFiles({
      name:'browser-verification.txt',mimeType:'text/plain',buffer:Buffer.from('验收用制度，异常复核应保存批次凭证。'),
    })
    await mobile.getByRole('button',{name:'上传并索引'}).click()
    await mobile.getByText('制度文档已上传并建立索引',{exact:true}).waitFor()
    assert.deepEqual(issues,[])
    const result = {status:'passed',target,engine:browser.version(),screenSizes:[[1440,960],[390,844]],
      modelConfigured:model,analysisMode:frame.draft.mode,orders:frame.draft.metrics.current.orders,
      refundedOrders:frame.draft.metrics.current.refundedOrders,steps:frame.draft.steps.map(item=>item.stage),
      citations:frame.draft.citations.map(item=>item.id),reportId:saved,crossStoreStatus:denied,documentUpload:true}
    fs.writeFileSync(path.join(evidence,'browser.json'),JSON.stringify(result,null,2))
    process.stdout.write(JSON.stringify(result,null,2)+'\n')
  } finally {
    await browser.close()
  }
}

run().catch(error => {
  process.stderr.write(error.message + '\n')
  process.exitCode = 1
})
