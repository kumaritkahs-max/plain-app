<template>
  <div class="screen-capture">
    <Teleport v-if="isActive" to="#header-start-slot" defer>
      <div class="title">{{ $t('screen_capture') }}</div>
    </Teleport>

    <Teleport v-if="isActive" to="#header-end-slot" defer>
      <div class="header-actions">
        <v-icon-button :tooltip="$t('view_recordings')" @click="goRecordings">
          <i-lucide:film />
        </v-icon-button>
        <v-filled-button v-if="serviceRunning" class="btn-sm" :loading="stopSvcLoading" @click="stopService">
          {{ $t('stop_screen_capture') }}
        </v-filled-button>
      </div>
    </Teleport>

    <div class="stage">
      <div v-if="!serviceRunning" class="idle-panel">
        <i-material-symbols:screen-record-rounded class="idle-icon" />
        <p class="idle-title">{{ $t('screen_capture') }}</p>
        <p class="idle-hint">{{ $t('screen_capture_idle_hint') }}</p>
        <v-filled-button :loading="startSvcLoading" @click="startService">
          {{ $t('start_screen_capture') }}
        </v-filled-button>
      </div>
      <div v-else class="active-panel">
        <i-material-symbols:screen-record-rounded class="active-icon" :class="{ recording }" />
        <p class="active-title">{{ $t('screen_capture_running') }}</p>
        <div v-if="recording" class="rec-badge"><span class="rec-dot" />{{ $t('recording_now') }}</div>
        <div class="action-row">
          <v-filled-button v-if="!recording" :loading="recStartLoading" @click="startRec">
            <i-lucide:circle-dot class="btn-icon" />
            {{ $t('start_screen_recording') }}
          </v-filled-button>
          <v-filled-button v-else :loading="recStopLoading" @click="stopRec">
            <i-lucide:square class="btn-icon" />
            {{ $t('stop_screen_recording') }}
          </v-filled-button>
          <v-outlined-button :loading="shotLoading" @click="takeShot">
            <i-lucide:image class="btn-icon" />
            {{ $t('take_screenshot') }}
          </v-outlined-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import toast from '@/components/toaster'
import { openModal } from '@/components/modal'
import RecordingSaveModal from '@/components/RecordingSaveModal.vue'
import type { GqlError } from '@/lib/api/gql-client'
import { initLazyQuery, screenCaptureStateGQL } from '@/lib/api/query'
import {
  initMutation,
  startScreenCaptureServiceGQL,
  stopScreenCaptureServiceGQL,
  startScreenRecordingGQL,
  stopScreenRecordingGQL,
  takeScreenshotGQL,
} from '@/lib/api/mutation'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const isActive = computed(() => route.path === '/screen-capture')

const serviceRunning = ref(false)
const recording = ref(false)

const { fetch: fetchState } = initLazyQuery({
  handle: (data: any, error: string) => {
    if (error) return
    const s = data?.screenCaptureState
    serviceRunning.value = !!s?.running
    recording.value = !!s?.recording
  },
  document: screenCaptureStateGQL,
  variables: () => ({}),
  options: { fetchPolicy: 'no-cache' },
})

const { mutate: startSvcMutate, loading: startSvcLoading, onDone: onSvcStarted, onError: onSvcStartError } =
  initMutation({ document: startScreenCaptureServiceGQL })
const { mutate: stopSvcMutate, loading: stopSvcLoading, onDone: onSvcStopped } =
  initMutation({ document: stopScreenCaptureServiceGQL })
const { mutate: recStartMutate, loading: recStartLoading, onDone: onRecStarted, onError: onRecStartError } =
  initMutation({ document: startScreenRecordingGQL })
const { loading: recStopLoading } = initMutation({ document: stopScreenRecordingGQL })
const { loading: shotLoading } = initMutation({ document: takeScreenshotGQL })

const startService = () => startSvcMutate({})
const stopService = () => stopSvcMutate({})
const startRec = () => recStartMutate({})

onSvcStarted(() => {
  // user must accept the screen-capture system dialog; poll briefly
  setTimeout(() => fetchState(), 1500)
})
onSvcStartError((e: GqlError) => toast(t(e.message), 'error'))
onSvcStopped(() => { serviceRunning.value = false; recording.value = false })
onRecStarted(() => { recording.value = true; toast(t('recording_now')) })
onRecStartError((e: GqlError) => toast(t(e.message), 'error'))

function defaultName(prefix: string): string {
  const d = new Date()
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${prefix}-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
}

const stopRec = () => {
  openModal(RecordingSaveModal, {
    title: t('save_screen_recording'),
    defaultName: defaultName('SCR'),
    document: stopScreenRecordingGQL,
    onSaved: () => { recording.value = false; toast(t('recording_saved')) },
    onCancel: () => { recording.value = false },
  })
}

const takeShot = () => {
  openModal(RecordingSaveModal, {
    title: t('save_screenshot'),
    defaultName: defaultName('SHOT'),
    document: takeScreenshotGQL,
    onSaved: () => toast(t('screenshot_saved')),
  })
}

const goRecordings = () => router.push('/recordings?type=screen')

let pollTimer: any = null
onMounted(() => {
  fetchState()
  pollTimer = setInterval(() => fetchState(), 3000)
})
onBeforeUnmount(() => { if (pollTimer) clearInterval(pollTimer) })
</script>

<style scoped lang="scss">
.screen-capture { display: flex; flex-direction: column; height: 100%; }
.title { flex: 1; font-weight: 500; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.stage { flex: 1; display: flex; align-items: center; justify-content: center; padding: 16px; }
.idle-panel, .active-panel {
  display: flex; flex-direction: column; align-items: center; gap: 12px;
  padding: 32px; border-radius: 16px;
  background: var(--md-sys-color-surface-container);
  min-width: 320px;
}
.idle-icon, .active-icon { font-size: 48px; color: var(--md-sys-color-primary); }
.active-icon.recording { color: var(--md-sys-color-error); animation: pulse 1.4s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 0.6; } 50% { opacity: 1; } }
.idle-title, .active-title { font-size: 1.25rem; font-weight: 500; margin: 0; }
.idle-hint { color: var(--md-sys-color-on-surface-variant); margin: 0; text-align: center; max-width: 360px; }
.action-row { display: flex; gap: 12px; flex-wrap: wrap; justify-content: center; margin-top: 8px; }
.btn-icon { margin-right: 6px; }
.rec-badge {
  display: inline-flex; align-items: center; gap: 6px;
  background: rgba(244, 67, 54, 0.9); color: #fff;
  padding: 4px 10px; border-radius: 999px; font-size: 0.75rem; font-weight: 600;
}
.rec-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%;
  background: #fff; animation: blink 1s ease-in-out infinite; }
@keyframes blink { 0%, 100% { opacity: 0.3; } 50% { opacity: 1; } }
</style>
