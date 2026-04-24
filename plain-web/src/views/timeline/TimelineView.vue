<template>
  <div class="timeline-page">
    <div class="page-header">
      <h2><i-lucide:activity /> {{ $t('page_title.timeline') }}</h2>
      <p class="muted">{{ $t('timeline_desc') }}</p>
      <div class="row gap" style="margin-top: 8px">
        <span class="muted" v-if="store.serverStartedAt">{{ $t('since') }}: {{ new Date(store.serverStartedAt).toLocaleString() }}</span>
        <span class="muted">·</span>
        <span class="muted">{{ store.events.length }} {{ $t('events') }}</span>
        <v-outlined-button class="btn-sm" @click="loadAll">{{ $t('refresh') }}</v-outlined-button>
      </div>
    </div>

    <div v-if="store.events.length === 0" class="empty">{{ $t('waiting_events') }}</div>

    <div v-else class="timeline">
      <div v-for="e in store.events" :key="e.id" class="event">
        <div class="line">
          <div class="dot" :style="{ background: typeColor(e.type) }">
            <component :is="typeIcon(e.type)" />
          </div>
          <div class="bar"></div>
        </div>
        <div class="content">
          <div class="time">{{ formatTime(e.time) }} <span class="date">{{ formatDate(e.time) }}</span></div>
          <div class="title">
            <img v-if="e.appId" :src="iconUrl(e.appId)" class="app-icon" width="18" height="18" />
            <span class="arrow">→</span>
            <span class="app-name" v-if="e.appName">{{ e.appName }}: </span>
            {{ e.title }}
          </div>
          <div v-if="e.subtitle" class="sub">{{ e.subtitle }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, onUnmounted } from 'vue'
import emitter from '@/plugins/eventbus'
import { gqlFetch } from '@/lib/api/gql-client'
import { timelineEntriesGQL } from '@/lib/api/query'
import { useTimelineStore } from '@/stores/timeline'
import { useTempStore } from '@/stores/temp'
import { storeToRefs } from 'pinia'
import { getFileUrlByPath } from '@/lib/api/file'

const store = useTimelineStore()
const { urlTokenKey } = storeToRefs(useTempStore())
function iconUrl(pkg: string) { return getFileUrlByPath(urlTokenKey.value, 'pkgicon://' + pkg) }

function formatTime(t: number) { return new Date(t).toLocaleTimeString() }
function formatDate(t: number) { return new Date(t).toLocaleDateString() }
function typeColor(t: string) {
  return ({
    notification: '#5a8dee', launch: '#39c5bb', system: '#a37bf3',
    location: '#f7b500', screenshot: '#ec5b80', clipboard: '#56c596', call: '#e91e63',
  } as any)[t] || '#888'
}
function typeIcon(type: string) {
  const ch = ({ notification: '!', launch: '▶', system: '✓', location: '⌖', screenshot: '◫', clipboard: '⧉', call: '☏' } as any)[type] || '•'
  return () => h('span', { style: { color: 'white', fontSize: '12px', fontWeight: 700 } }, ch)
}

function onTimelineEvent(d: any) {
  if (!d) return
  store.add({ id: `s${d.id}`, type: d.type, title: d.title, subtitle: d.subtitle || '', appId: d.appId || '', appName: d.appName || '', time: d.time })
}

async function loadAll() {
  try {
    const r = await gqlFetch<{ timelineEntries: any[]; timelineStartedAt: number }>(timelineEntriesGQL, { limit: 500 })
    if (r.errors?.length) return
    store.setServerStart(r.data.timelineStartedAt)
    for (const e of r.data.timelineEntries || []) {
      store.add({ id: `s${e.id}`, type: e.type, title: e.title, subtitle: e.subtitle || '', appId: e.appId || '', appName: e.appName || '', time: e.time })
    }
  } catch (e) { console.error(e) }
}

onMounted(() => {
  emitter.on('timeline_event', onTimelineEvent)
  loadAll()
})
onUnmounted(() => {
  emitter.off('timeline_event', onTimelineEvent)
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
.title { font-weight: 500; margin-top: 2px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.app-icon { border-radius: 4px; vertical-align: middle; }
.app-name { font-weight: 600; }
.arrow { color: var(--md-sys-color-primary); margin-right: 4px; }
.sub { color: var(--md-sys-color-on-surface-variant); font-size: 0.875rem; margin-top: 4px; word-break: break-word; }
</style>
