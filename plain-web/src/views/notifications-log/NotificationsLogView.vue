<template>
  <div class="nlog-page">
    <div class="page-header">
      <h2><i-lucide:bell /> {{ $t('page_title.notifications_log') }}</h2>
      <p class="muted">{{ $t('notifications_log_desc') }}</p>
      <div class="meta">
        <span>{{ $t('total') }}: {{ totalCount }}</span>
        <span>{{ $t('unique') }}: {{ groups.length }}</span>
      </div>
    </div>

    <div v-if="groups.length === 0" class="empty">
      <i-lucide:bell-off />
      <p>{{ $t('waiting_notifications') }}</p>
    </div>

    <div class="cards">
      <div v-for="g in groups" :key="g.key" class="card">
        <img class="icon" :src="g.icon" width="40" height="40" />
        <div class="body">
          <div class="line1">
            <span class="app">{{ g.appName }}</span>
            <span class="time">{{ formatDateTime(g.lastTime) }}</span>
          </div>
          <div class="title">{{ g.title }}</div>
          <div class="text">{{ g.body }}</div>
          <div v-if="g.expanded" class="times">
            <span v-for="t in g.times" :key="t" class="time-chip">{{ formatTime(t) }}</span>
          </div>
        </div>
        <button class="count-btn" :title="$t('show_times')" @click="g.expanded = !g.expanded">
          ×{{ g.count }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import emitter from '@/plugins/eventbus'
import { useTempStore } from '@/stores/temp'
import { storeToRefs } from 'pinia'
import { getFileUrlByPath } from '@/lib/api/file'
import { useNotificationsStore } from '@/stores/notifications-log'
import { gqlFetch } from '@/lib/api/gql-client'
import { notificationLogGQL } from '@/lib/api/query'

const { urlTokenKey } = storeToRefs(useTempStore())
const store = useNotificationsStore()
const groups = computed(() => store.groups)

function formatDateTime(t: number | string) {
  const d = typeof t === 'number' ? new Date(t) : new Date(t)
  return d.toLocaleString()
}
function formatTime(t: number) { return new Date(t).toLocaleTimeString() }
const totalCount = computed(() => groups.value.reduce((a, g) => a + g.count, 0))

function ingest(data: any) {
  if (!data) return
  const ts = data.time ? new Date(data.time).getTime() : Date.now()
  store.add({
    appId: data.appId,
    appName: data.appName || data.appId,
    icon: getFileUrlByPath(urlTokenKey.value, 'pkgicon://' + data.appId),
    title: data.title || '',
    body: data.body || '',
    time: ts,
  })
}

async function loadInitial() {
  try {
    const r = await gqlFetch<{ notificationLog: any[] }>(notificationLogGQL)
    if (r.errors?.length) return
    const arr = r.data.notificationLog || []
    for (const n of arr) ingest(n)
  } catch (e) { console.error(e) }
}

onMounted(() => {
  emitter.on('notification_created', ingest)
  if (store.groups.length === 0) loadInitial()
})
onUnmounted(() => {
  emitter.off('notification_created', ingest)
})
</script>

<style scoped lang="scss">
.nlog-page { padding: 16px; }
.page-header { margin-bottom: 16px; h2 { display: flex; align-items: center; gap: 8px; margin: 0 0 4px; } }
.meta { display: flex; gap: 16px; font-size: 0.8125rem; color: var(--md-sys-color-on-surface-variant); margin-top: 6px; }
.muted { color: var(--md-sys-color-on-surface-variant); font-size: 0.875rem; }
.empty { text-align: center; padding: 60px 20px; color: var(--md-sys-color-on-surface-variant); svg { width: 48px; height: 48px; opacity: 0.5; } }
.cards { display: grid; gap: 12px; }
.card {
  display: grid; grid-template-columns: 40px 1fr auto; gap: 14px;
  background: var(--md-sys-color-surface-container); padding: 14px; border-radius: 14px;
  align-items: flex-start;
}
.icon { border-radius: 8px; }
.body { min-width: 0; }
.line1 { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.app { font-weight: 600; font-size: 0.875rem; }
.time { font-size: 0.75rem; color: var(--md-sys-color-on-surface-variant); }
.title { font-weight: 500; margin-top: 2px; }
.text { color: var(--md-sys-color-on-surface-variant); font-size: 0.875rem; margin-top: 2px; word-break: break-word; }
.times { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
.time-chip {
  background: var(--md-sys-color-surface-variant); color: var(--md-sys-color-on-surface-variant);
  font-size: 0.75rem; padding: 2px 8px; border-radius: 999px;
}
.count-btn {
  background: var(--md-sys-color-primary); color: var(--md-sys-color-on-primary);
  border: none; padding: 6px 12px; border-radius: 999px; font-weight: 700; cursor: pointer;
  min-width: 48px;
}
</style>
