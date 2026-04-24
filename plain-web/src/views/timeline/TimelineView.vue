<template>
  <div class="timeline-page">
    <div class="page-header">
      <h2><i-lucide:activity /> {{ $t('page_title.timeline') }}</h2>
      <p class="muted">{{ $t('timeline_desc') }}</p>
      <div class="row gap" style="margin-top: 8px">
        <span class="muted">{{ $t('since') }}: {{ new Date(startedAt).toLocaleString() }}</span>
        <span class="muted">·</span>
        <span class="muted">{{ events.length }} {{ $t('events') }}</span>
        <v-outlined-button class="btn-sm" @click="loadHistoryNow">{{ $t('refresh') }}</v-outlined-button>
      </div>
    </div>

    <div v-if="events.length === 0" class="empty">{{ $t('waiting_events') }}</div>

    <div v-else class="timeline">
      <div v-for="e in events" :key="e.id" class="event">
        <div class="line">
          <div class="dot" :style="{ background: typeColor(e.type) }">
            <component :is="typeIcon(e.type)" />
          </div>
          <div class="bar"></div>
        </div>
        <div class="content">
          <div class="time">{{ formatTime(e.time) }} <span class="date">{{ formatDate(e.time) }}</span></div>
          <div class="title">
            <span class="arrow">→</span>
            {{ e.title }}
          </div>
          <div v-if="e.subtitle" class="sub">{{ e.subtitle }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { h, ref, onMounted, onUnmounted, computed } from 'vue'
import emitter from '@/plugins/eventbus'
import { useI18n } from 'vue-i18n'
import { gqlFetch } from '@/lib/api/gql-client'
import { launchHistoryGQL } from '@/lib/api/query'

interface Event { id: string; type: string; title: string; subtitle?: string; time: number }

const { t } = useI18n()
const startedAt = Date.now()
const events = ref<Event[]>([
  { id: 'start', type: 'system', title: t('server_connected'), time: startedAt },
])

let counter = 0
function add(e: Omit<Event, 'id'>) {
  if (e.time < startedAt - 5000) return
  events.value = [{ ...e, id: `e${++counter}` }, ...events.value].slice(0, 500)
}

function formatTime(t: number) { return new Date(t).toLocaleTimeString() }
function formatDate(t: number) { return new Date(t).toLocaleDateString() }
function typeColor(t: string) {
  return ({
    notification: '#5a8dee', launch: '#39c5bb', system: '#a37bf3',
    location: '#f7b500', screenshot: '#ec5b80', clipboard: '#56c596',
  } as any)[t] || '#888'
}
function typeIcon(type: string) {
  // simple text fallback so we don't need extra icon imports
  const ch = ({ notification: '!', launch: '▶', system: '✓', location: '⌖', screenshot: '◫', clipboard: '⧉' } as any)[type] || '•'
  return () => h('span', { style: { color: 'white', fontSize: '12px', fontWeight: 700 } }, ch)
}

function onNotification(data: any) {
  if (!data) return
  add({
    type: 'notification',
    title: `${data.appName || data.appId}: ${data.title || ''}`.trim(),
    subtitle: data.body || undefined,
    time: data.time ? new Date(data.time).getTime() : Date.now(),
  })
}
function onScreenMirroring() { add({ type: 'screenshot', title: t('screen_mirroring_event'), time: Date.now() }) }
function onMicStreaming() { add({ type: 'system', title: t('live_mic_event'), time: Date.now() }) }
function onCameraStreaming() { add({ type: 'system', title: t('live_camera_event'), time: Date.now() }) }

let pollHandle: any
const seenLaunch = new Set<string>()
async function loadHistoryNow() {
  try {
    const r = await gqlFetch<{ launchHistory: { packageId: string; timestamp: number }[] }>(launchHistoryGQL, { limit: 100 })
    if (r.errors) return
    for (const item of r.data.launchHistory.slice().reverse()) {
      const k = `${item.packageId}|${item.timestamp}`
      if (seenLaunch.has(k)) continue
      seenLaunch.add(k)
      if (item.timestamp >= startedAt) {
        add({ type: 'launch', title: t('opened_app', { app: item.packageId }), time: item.timestamp })
      }
    }
  } catch {}
}

onMounted(() => {
  emitter.on('notification_created', onNotification)
  emitter.on('screen_mirroring', onScreenMirroring)
  emitter.on('live_mic_streaming', onMicStreaming)
  emitter.on('live_camera_streaming', onCameraStreaming)
  loadHistoryNow()
  pollHandle = setInterval(loadHistoryNow, 5000)
})
onUnmounted(() => {
  emitter.off('notification_created', onNotification)
  emitter.off('screen_mirroring', onScreenMirroring)
  emitter.off('live_mic_streaming', onMicStreaming)
  emitter.off('live_camera_streaming', onCameraStreaming)
  if (pollHandle) clearInterval(pollHandle)
})
</script>

<style scoped>
.timeline-page { padding: 16px; max-width: 900px; margin: 0 auto; }
.page-header h2 { display: flex; align-items: center; gap: 8px; margin: 0 0 4px; }
.muted { color: var(--md-sys-color-on-surface-variant); font-size: 0.875rem; }
.row.gap { display: flex; gap: 10px; align-items: center; }
.empty { text-align: center; padding: 60px; color: var(--md-sys-color-on-surface-variant); }
.timeline { padding: 24px 0; }
.event { display: grid; grid-template-columns: 56px 1fr; gap: 12px; align-items: stretch; min-height: 64px; }
.line { position: relative; display: flex; flex-direction: column; align-items: center; }
.dot { width: 32px; height: 32px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex-shrink: 0; box-shadow: 0 2px 6px rgba(0,0,0,0.15); z-index: 1; }
.bar { width: 2px; flex: 1; background: var(--md-sys-color-outline-variant); margin-top: 4px; }
.event:last-child .bar { display: none; }
.content {
  background: var(--md-sys-color-surface-container); border-radius: 12px; padding: 10px 14px;
  margin-bottom: 12px;
}
.time { font-size: 0.75rem; color: var(--md-sys-color-on-surface-variant); }
.date { margin-left: 8px; }
.title { font-weight: 500; margin-top: 2px; }
.arrow { color: var(--md-sys-color-primary); margin-right: 4px; }
.sub { color: var(--md-sys-color-on-surface-variant); font-size: 0.875rem; margin-top: 4px; word-break: break-word; }
</style>
