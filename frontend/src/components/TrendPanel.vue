<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Dashboard } from '../types'

const props = defineProps<{ days: Dashboard['trend'] }>()
const options = [{ id: 'sales', label: '销售额' }, { id: 'orders', label: '订单与退款' }, { id: 'rate', label: '退款率' }] as const
const mode = ref<typeof options[number]['id']>('orders')
const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })
const series = computed(() => props.days.map(day => {
  const rate = day.orders ? day.refundedOrders * 100 / day.orders : null
  if (mode.value === 'sales') return { day: day.day, main: day.grossSales, extra: 0, label: currency.format(day.grossSales) }
  if (mode.value === 'rate') return { day: day.day, main: rate ?? 0, extra: 0, label: rate == null ? '—' : rate.toFixed(2) + '%' }
  return { day: day.day, main: day.orders, extra: day.refundedOrders, label: day.orders + ' 笔订单，' + day.refundedOrders + ' 笔退款' }
}))
const max = computed(() => Math.max(1, ...series.value.map(item => item.main)))
const unit = computed(() => mode.value === 'sales' ? '元' : mode.value === 'rate' ? '%' : '笔')
</script>

<template>
  <section class="card trend">
    <div class="section-title trend-header">
      <div><h2>经营趋势</h2><p class="muted">按日汇总 · {{ unit }}</p></div>
      <div class="segmented" aria-label="趋势指标">
        <button v-for="option in options" :key="option.id" :aria-pressed="mode===option.id" @click="mode=option.id">{{ option.label }}</button>
      </div>
    </div>
    <p v-if="!series.length" class="empty-note">该区间暂无订单。</p>
    <template v-else>
      <div v-if="mode==='orders'" class="legend"><span>订单</span><span>退款订单</span></div>
      <div class="trend-scroll" tabindex="0" aria-label="逐日经营趋势">
        <ul class="trend-columns" :style="{ '--day-count': series.length }">
          <li v-for="item in series" :key="item.day" :aria-label="item.day + ' ' + item.label">
            <div class="trend-bars" :title="item.day + ' · ' + item.label">
              <i :style="{height:item.main/max*100+'%'}"></i><i v-if="mode==='orders'" class="refund-column" :style="{height:item.extra/max*100+'%'}"></i>
            </div>
            <span>{{ item.day.slice(5) }}</span>
            <strong>{{ mode==='orders' ? item.main + ' / ' + item.extra : item.label }}</strong>
          </li>
        </ul>
      </div>
    </template>
  </section>
</template>
