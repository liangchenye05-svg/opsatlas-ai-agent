<script setup lang="ts">
import type { Citation } from '../types'
defineProps<{ items: Citation[] }>()
const emit = defineEmits<{ open: [reference: Citation] }>()
</script>

<template>
  <div class="citation-list">
    <button v-for="item in items" :key="item.id" type="button" @click="emit('open',item)">
      <strong>{{ item.title }} · 第 {{ item.ordinal }} 段</strong>
      <small>{{ item.source }}</small>
      <span>{{ item.content }}</span>
    </button>
  </div>
</template>
