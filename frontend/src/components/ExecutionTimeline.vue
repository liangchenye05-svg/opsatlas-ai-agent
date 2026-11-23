<script setup lang="ts">
import type { Draft, Step } from '../types'

defineProps<{ steps: Step[]; pending: boolean; failure: string; draft?: Draft | null }>()
const labels: Record<string,string> = {
  authorized:'身份与门店权限',
  get_store_metrics:'经营数据查询',
  search_company_policy:'企业制度检索',
  verified:'证据核对'
}
</script>

<template>
  <section class="card execution-card">
    <div class="section-title">
      <h2>执行过程</h2>
      <span class="pill" :class="{ 'pill-error': failure, 'pill-success': draft }">{{ failure ? '失败' : pending ? '执行中' : draft ? '已完成' : '待开始' }}</span>
    </div>
    <p v-if="!steps.length && !pending && !failure" class="empty-note">提交分析后，这里会展示工具执行记录。</p>
    <ol v-if="steps.length" class="execution-list" aria-live="polite">
      <li v-for="(step, index) in steps" :key="index">
        <details>
          <summary><strong>{{ labels[step.stage] || step.stage }}</strong><span>已完成 · {{ step.elapsedMs }} ms</span></summary>
          <p>{{ step.message }}</p>
          <dl v-if="draft && step.stage==='get_store_metrics'" class="step-values">
            <dt>门店范围</dt><dd>{{ draft.metrics.storeIds.join('、') }}</dd>
            <dt>订单数</dt><dd>{{ draft.metrics.current.orders }}</dd>
            <dt>退款订单</dt><dd>{{ draft.metrics.current.refundedOrders }}</dd>
          </dl>
          <p v-if="draft && step.stage==='search_company_policy'">收到 {{ draft.citations.length }} 条可回查片段。</p>
        </details>
      </li>
    </ol>
    <p v-if="pending" class="run-wait" role="status">等待下一条服务端事件…</p>
    <p v-if="failure" class="run-failed" role="alert">{{ failure }}</p>
  </section>
</template>
