<template>
  <div class="recordings">
    <Teleport v-if="isActive" to="#header-start-slot" defer>
      <div class="title">{{ $t('recordings') }}</div>
    </Teleport>

    <div class="filters">
      <button
        v-for="opt in typeOptions"
        :key="opt.value"
        class="chip"
        :class="{ active: typeFilter === opt.value }"
        @click="setType(opt.value)"
      >
        {{ opt.label }}
        <span v-if="opt.count >= 0" class="chip-count">{{ opt.count }}</span>
      </button>
    </div>

    <div v-if="loading && items.length === 0" class="empty">
      <i-lucide:loader-circle class="spinner" />
    </div>
    <div v-else-if="items.length === 0" class="empty">
      <i-lucide:film class="empty-icon" />
      <p>{{ $t('no_recordings') }}</p>
    </div>
    <div v-else class="grid">
      <div
        v-for="item in items"
        :key="item.id"
        class="card r-card"
        @click="openItem(item)"
      >
        <div class="thumb" :class="`thumb-${item.type}`">
          <video
            v-if="item.type === 'video' || item.type === 'screen'"
            :src="mediaUrl(item)"
            class="thumb-media"
            preload="metadata"
            muted
          />
          <img
            v-else-if="item.type === 'photo' || item.type === 'screenshot'"
            :src="mediaUrl(item)"
            class="thumb-media"
            loading="lazy"
            alt=""
          />
          <div v-else class="thumb-audio">
            <i-lucide:music />
          </div>
          <div class="badge type-badge">{{ typeLabel(item.type) }}</div>
          <div v-if="item.durationMs > 0" class="badge dur-badge">
            {{ formatDuration(item.durationMs) }}
          </div>
        </div>
        <div class="meta">
          <div class="r-name" :title="item.name">{{ item.name }}</div>
          <div class="r-sub">
            <span>{{ formatBytes(item.sizeBytes) }}</span>
            <span class="dot">•</span>
            <span>{{ formatTime(item.createdAt) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Viewer modal -->
    <Teleport v-if="viewerItem" to="body">
      <div class="viewer-backdrop" @click.self="closeViewer">
        <div class="viewer">
          <header class="viewer-head">
            <div class="viewer-title">
              <span class="badge type-badge inline">{{ typeLabel(viewerItem.type) }}</span>
              {{ viewerItem.name }}
            </div>
            <div class="viewer-actions">
              <v-icon-button :tooltip="$t('rename_recording')" @click="renameItem(viewerItem)">
                <i-lucide:pencil />
              </v-icon-button>
              <v-icon-button :tooltip="$t('download_recording')" @click="downloadItem(viewerItem)">
                <i-lucide:download />
              </v-icon-button>
              <v-icon-button :tooltip="$t('delete_recording')" @click="deleteItem(viewerItem)">
                <i-lucide:trash-2 />
              </v-icon-button>
              <v-icon-button :tooltip="$t('close')" @click="closeViewer">
                <i-lucide:x />
              </v-icon-button>
            </div>
          </header>
          <div class="viewer-body">
            <video
              v-if="viewerItem.type === 'video' || viewerItem.type === 'screen'"
              :src="mediaUrl(viewerItem)"
              controls
              autoplay
              class="viewer-media"
            />
            <img
              v-else-if="viewerItem.type === 'photo' || viewerItem.type === 'screenshot'"
              :src="mediaUrl(viewerItem)"
              class="viewer-media"
              alt=""
            />
            <audio
              v-else
              :src="mediaUrl(viewerItem)"
              controls
              autoplay
              class="viewer-audio"
            />
          </div>
          <footer class="viewer-foot">
            <div v-if="viewerItem.note" class="viewer-note">{{ viewerItem.note }}</div>
            <div class="viewer-info">
              <span>{{ formatBytes(viewerItem.sizeBytes) }}</span>
              <span class="dot">•</span>
              <span>{{ formatTime(viewerItem.createdAt) }}</span>
              <span v-if="viewerItem.durationMs > 0" class="dot">•</span>
              <span v-if="viewerItem.durationMs > 0">{{ formatDuration(viewerItem.durationMs) }}</span>
              <span v-if="viewerItem.width > 0" class="dot">•</span>
              <span v-if="viewerItem.width > 0">{{ viewerItem.width }}×{{ viewerItem.height }}</span>
            </div>
          </footer>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import toast from '@/components/toaster'
import { openModal } from '@/components/modal'
import PromptModal from '@/components/PromptModal.vue'
import {
  initLazyQuery,
  recordingsGQL,
  recordingsStatsGQL,
} from '@/lib/api/query'
import {
  initMutation,
  renameRecordingGQL,
  deleteRecordingGQL,
} from '@/lib/api/mutation'
import { getApiBaseUrl } from '@/lib/api/api'
import { gqlFetch } from '@/lib/api/gql-client'

interface RecItem {
  id: string
  type: string
  name: string
  note: string
  tags: string
  durationMs: number
  sizeBytes: number
  width: number
  height: number
  mimeType: string
  createdAt: string
}

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const isActive = computed(() => route.path === '/recordings')

const typeFilter = ref<string>(((route.query.type as string) || ''))
const items = ref<RecItem[]>([])
const loading = ref(false)
const viewerItem = ref<RecItem | null>(null)
const stats = ref<any>({})

const typeOptions = computed(() => {
  const s = stats.value || {}
  return [
    { value: '', label: t('recording_type_all'), count: Number(s.total ?? -1) },
    { value: 'video', label: t('recording_type_video'), count: Number(s.videoCount ?? -1) },
    { value: 'photo', label: t('recording_type_photo'), count: Number(s.photoCount ?? -1) },
    { value: 'audio', label: t('recording_type_audio'), count: Number(s.audioCount ?? -1) },
    { value: 'screen', label: t('recording_type_screen'), count: Number(s.screenCount ?? -1) },
    { value: 'screenshot', label: t('recording_type_screenshot'), count: Number(s.screenshotCount ?? -1) },
  ]
})

function setType(v: string) {
  typeFilter.value = v
  router.replace({ path: '/recordings', query: v ? { type: v } : {} })
  load()
}

function typeLabel(t0: string) {
  switch (t0) {
    case 'video': return t('recording_type_video')
    case 'photo': return t('recording_type_photo')
    case 'audio': return t('recording_type_audio')
    case 'screen': return t('recording_type_screen')
    case 'screenshot': return t('recording_type_screenshot')
    default: return t0
  }
}

function mediaUrl(item: RecItem, download = false): string {
  const cid = encodeURIComponent(localStorage.getItem('client_id') ?? '')
  const dl = download ? '&download=1' : ''
  return `${getApiBaseUrl()}/recordings/${encodeURIComponent(item.id)}?c=${cid}${dl}`
}

function formatBytes(n: number) {
  if (!n || n <= 0) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = n
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(v >= 10 ? 0 : 1)} ${u[i]}`
}

function formatDuration(ms: number) {
  const s = Math.round(ms / 1000)
  const m = Math.floor(s / 60)
  const ss = (s % 60).toString().padStart(2, '0')
  return `${m}:${ss}`
}

function formatTime(iso: string) {
  try { return new Date(iso).toLocaleString() } catch { return iso }
}

const { fetch: fetchStats } = initLazyQuery({
  handle: (data: any) => { stats.value = data?.recordingsStats || {} },
  document: recordingsStatsGQL,
  variables: () => ({}),
  options: { fetchPolicy: 'no-cache' },
})

async function load() {
  loading.value = true
  try {
    const r = await gqlFetch(recordingsGQL, { type: typeFilter.value, offset: 0, limit: 500 })
    items.value = (r?.data?.recordings ?? []) as RecItem[]
  } catch (e: any) {
    toast(e?.message || 'network_error', 'error')
  } finally {
    loading.value = false
  }
  fetchStats()
}

function openItem(item: RecItem) { viewerItem.value = item }
function closeViewer() { viewerItem.value = null }

const { mutate: renameMutate, onDone: onRenamed } = initMutation({ document: renameRecordingGQL })
onRenamed((r: any) => {
  const updated = r?.data?.renameRecording
  if (updated) {
    items.value = items.value.map(i => i.id === updated.id ? updated : i)
    if (viewerItem.value?.id === updated.id) viewerItem.value = updated
  }
})

function renameItem(item: RecItem) {
  openModal(PromptModal, {
    title: t('rename_recording'),
    value: item.name,
    do: (v: string) => renameMutate({ id: item.id, name: v }),
  })
}

const { mutate: deleteMutate, onDone: onDeleted } = initMutation({ document: deleteRecordingGQL })
let pendingDeleteId: string | null = null
onDeleted(() => {
  if (pendingDeleteId) {
    items.value = items.value.filter(i => i.id !== pendingDeleteId)
    if (viewerItem.value?.id === pendingDeleteId) viewerItem.value = null
    pendingDeleteId = null
    fetchStats()
  }
})

function deleteItem(item: RecItem) {
  if (!confirm(t('confirm_to_delete_name', { name: item.name }))) return
  pendingDeleteId = item.id
  deleteMutate({ id: item.id })
}

function downloadItem(item: RecItem) {
  const a = document.createElement('a')
  a.href = mediaUrl(item, true)
  a.download = item.name
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

watch(() => route.query.type, (v) => {
  const nv = (v as string) || ''
  if (nv !== typeFilter.value) { typeFilter.value = nv; load() }
})

onMounted(() => { load() })
</script>

<style scoped lang="scss">
.recordings { display: flex; flex-direction: column; height: 100%; }
.title { flex: 1; font-weight: 500; }

.filters {
  display: flex; flex-wrap: wrap; gap: 8px;
  padding: 12px 16px; border-bottom: 1px solid var(--md-sys-color-outline-variant);
}
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 12px; border-radius: 999px;
  border: 1px solid var(--md-sys-color-outline);
  background: transparent; color: var(--md-sys-color-on-surface);
  cursor: pointer; font-size: 0.875rem;
}
.chip.active {
  background: var(--md-sys-color-primary);
  color: var(--md-sys-color-on-primary);
  border-color: transparent;
}
.chip-count {
  font-weight: 600;
  font-size: 0.75rem;
  background: rgba(0,0,0,0.1);
  padding: 1px 6px; border-radius: 999px;
}
.chip.active .chip-count { background: rgba(255,255,255,0.25); }

.empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; color: var(--md-sys-color-on-surface-variant); }
.empty-icon { font-size: 64px; }
.spinner { font-size: 32px; animation: spin 1.2s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  padding: 16px;
  overflow-y: auto;
}
.r-card {
  display: flex; flex-direction: column; cursor: pointer;
  border-radius: 12px; overflow: hidden;
  background: var(--md-sys-color-surface-container);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.r-card:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.12); }
.thumb {
  position: relative; aspect-ratio: 16 / 10;
  background: #000; display: flex; align-items: center; justify-content: center;
}
.thumb-audio { color: rgba(255,255,255,0.7); font-size: 48px; }
.thumb-audio :deep(svg) { width: 56px; height: 56px; }
.thumb-media { width: 100%; height: 100%; object-fit: cover; }
.badge {
  position: absolute; padding: 2px 8px; border-radius: 999px;
  font-size: 0.7rem; font-weight: 600; color: #fff;
  background: rgba(0,0,0,0.6);
}
.type-badge { top: 8px; left: 8px; background: rgba(33, 150, 243, 0.9); }
.dur-badge { bottom: 8px; right: 8px; }
.meta { padding: 10px 12px; }
.r-name { font-weight: 500; font-size: 0.95rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.r-sub {
  margin-top: 4px; font-size: 0.75rem; color: var(--md-sys-color-on-surface-variant);
  display: flex; align-items: center; gap: 6px;
}
.dot { opacity: 0.5; }

.viewer-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,0.85);
  display: flex; align-items: center; justify-content: center; z-index: 2000;
}
.viewer {
  background: var(--md-sys-color-surface-container-high);
  border-radius: 16px; max-width: 90vw; max-height: 92vh;
  display: flex; flex-direction: column; overflow: hidden; min-width: 480px;
}
.viewer-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px; border-bottom: 1px solid var(--md-sys-color-outline-variant);
}
.viewer-title { display: flex; align-items: center; gap: 10px; font-weight: 500; }
.type-badge.inline { position: static; }
.viewer-actions { display: flex; gap: 4px; }
.viewer-body {
  flex: 1; display: flex; align-items: center; justify-content: center;
  background: #000; padding: 8px; min-height: 240px;
}
.viewer-media { max-width: 86vw; max-height: 70vh; }
.viewer-audio { width: 80%; }
.viewer-foot {
  padding: 12px 16px; border-top: 1px solid var(--md-sys-color-outline-variant);
  display: flex; flex-direction: column; gap: 6px;
}
.viewer-note { color: var(--md-sys-color-on-surface); }
.viewer-info { font-size: 0.8rem; color: var(--md-sys-color-on-surface-variant); display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
</style>
