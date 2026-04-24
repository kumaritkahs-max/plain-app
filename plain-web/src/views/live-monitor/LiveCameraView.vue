<template>
  <div class="live-monitor">
    <Teleport v-if="isActive" to="#header-start-slot" defer>
      <div class="title">{{ $t('live_camera') }}</div>
    </Teleport>

    <Teleport v-if="isActive" to="#header-end-slot" defer>
      <div class="header-actions">
        <v-icon-button :tooltip="$t('view_recordings')" @click="goRecordings">
          <i-lucide:film />
        </v-icon-button>
        <v-icon-button v-if="streaming" :tooltip="$t('switch_camera')" @click="switchFacing">
          <i-lucide:repeat-2 />
        </v-icon-button>
        <v-filled-button v-if="streaming" class="btn-sm" :loading="stopLoading" @click="stop">
          {{ $t('stop_live_camera') }}
        </v-filled-button>
      </div>
    </Teleport>

    <div class="live-stage">
      <div v-if="state === 'idle' || state === 'failed' || state === 'requesting'" class="idle-panel">
        <i-lucide:camera class="idle-icon" />
        <p class="idle-title">{{ $t('live_camera') }}</p>
        <p class="idle-hint">{{ $t('live_camera_idle_hint') }}</p>
        <div class="facing-row">
          <v-segmented-button v-model="facing" :options="facingOptions" />
        </div>
        <v-filled-button :loading="startLoading" @click="start">{{ $t('start_live_camera') }}</v-filled-button>
        <p v-if="state === 'failed'" class="error-text">{{ $t('live_stream_failed') }}</p>
      </div>
      <div v-else class="video-wrap">
        <video ref="videoEl" class="live-video" autoplay playsinline muted />
        <div v-if="state !== 'streaming'" class="overlay">
          <i-lucide:loader-circle class="spinner" />
          <span>{{ $t('connecting') }}</span>
        </div>
        <div v-if="state === 'streaming'" class="rec-overlay">
          <div v-if="recording" class="rec-badge">
            <span class="rec-dot" />
            {{ $t('recording_now') }}
          </div>
          <div class="rec-actions">
            <v-icon-button v-if="!recording" :tooltip="$t('record')" :loading="recStartLoading" @click="startRec">
              <i-lucide:circle-dot class="rec-icon" />
            </v-icon-button>
            <v-icon-button v-else :tooltip="$t('stop_record')" :loading="recStopLoading" @click="stopRec">
              <i-lucide:square class="rec-icon stop" />
            </v-icon-button>
            <v-icon-button :tooltip="$t('take_photo')" :loading="photoLoading" @click="snap">
              <i-lucide:camera />
            </v-icon-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import emitter from '@/plugins/eventbus'
import toast from '@/components/toaster'
import { openModal } from '@/components/modal'
import RecordingSaveModal from '@/components/RecordingSaveModal.vue'
import type { GqlError } from '@/lib/api/gql-client'
import { initLazyQuery, liveCameraStateGQL, cameraRecordingStateGQL } from '@/lib/api/query'
import {
  initMutation,
  startLiveCameraGQL,
  stopLiveCameraGQL,
  switchLiveCameraFacingGQL,
  startCameraVideoRecordingGQL,
  stopCameraVideoRecordingGQL,
  captureCameraPhotoGQL,
} from '@/lib/api/mutation'
import { WebRTCClient, type SignalingMessage } from '@/lib/webrtc-client'
import { makeSendWebRTCSignalingFor } from '@/lib/webrtc-signaling'
import { getPhoneIp } from '@/lib/api/api'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const isActive = computed(() => route.path === '/live-camera')

type LiveState = 'idle' | 'requesting' | 'connecting' | 'streaming' | 'failed'
const state = ref<LiveState>('idle')
const facing = ref<'back' | 'front'>('back')
const videoEl = ref<HTMLVideoElement>()

const facingOptions = computed(() => [
  { value: 'back', label: t('camera_back') },
  { value: 'front', label: t('camera_front') },
])

let client: WebRTCClient | null = null
const queue: SignalingMessage[] = []

function attach(stream: MediaStream) {
  if (videoEl.value) {
    videoEl.value.srcObject = stream
    videoEl.value.play().catch(() => {})
  }
}

function connect() {
  cleanupClient()
  client = new WebRTCClient({
    sendSignaling: makeSendWebRTCSignalingFor('camera'),
    onStream: (s) => { attach(s); state.value = 'streaming' },
    onConnectionStateChange: (s) => {
      if (s === 'failed' || s === 'disconnected' || s === 'closed') {
        if (state.value === 'streaming' || state.value === 'connecting') state.value = 'failed'
      }
    },
    onError: () => { state.value = 'failed' },
  })
  client.startSession(false, false, getPhoneIp(), { video: true, audio: false })
  while (queue.length) client.handleSignalingMessage(queue.shift()!)
}

function cleanupClient() {
  if (videoEl.value) { videoEl.value.pause(); videoEl.value.srcObject = null }
  client?.cleanup(); client = null
}

const onSignaling = (msg: any) => {
  if (!msg || msg.stream !== 'camera') return
  if (client) client.handleSignalingMessage(msg)
  else queue.push(msg)
}

const { fetch: fetchState } = initLazyQuery({
  handle: (data: any, error: string) => {
    if (error) { toast(t(error), 'error'); return }
    const s = data?.liveCameraState
    if (s?.facing) facing.value = s.facing === 'front' ? 'front' : 'back'
    if (s?.running) {
      if (state.value === 'idle' || state.value === 'failed') { state.value = 'connecting'; connect() }
    } else {
      cleanupClient(); state.value = 'idle'
    }
  },
  document: liveCameraStateGQL,
  variables: () => ({}),
  options: { fetchPolicy: 'no-cache' },
})

const { mutate: startMutate, loading: startLoading, onError: onStartError } =
  initMutation({ document: startLiveCameraGQL }, false)
const { mutate: stopMutate, loading: stopLoading, onDone: onStopDone, onError: onStopError } =
  initMutation({ document: stopLiveCameraGQL })
const { mutate: switchMutate } = initMutation({ document: switchLiveCameraFacingGQL })

const start = () => { state.value = 'requesting'; startMutate({ facing: facing.value }) }
const stop = () => stopMutate()
const switchFacing = () => switchMutate()

// ---- Local recording / photo capture ----
const recording = ref(false)

const { mutate: recStartMutate, loading: recStartLoading, onDone: onRecStarted, onError: onRecStartError } =
  initMutation({ document: startCameraVideoRecordingGQL })
const { loading: recStopLoading } = initMutation({ document: stopCameraVideoRecordingGQL })
const { loading: photoLoading } = initMutation({ document: captureCameraPhotoGQL })

const { fetch: fetchRecState } = initLazyQuery({
  handle: (data: any, error: string) => {
    if (error) return
    recording.value = !!data?.cameraRecordingState?.recording
  },
  document: cameraRecordingStateGQL,
  variables: () => ({}),
  options: { fetchPolicy: 'no-cache' },
})

const startRec = () => recStartMutate()
onRecStarted(() => { recording.value = true; toast(t('recording_now')) })
onRecStartError((e: GqlError) => toast(t(e.message), 'error'))

function defaultName(prefix: string): string {
  const d = new Date()
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${prefix}-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
}

const stopRec = () => {
  openModal(RecordingSaveModal, {
    title: t('save_recording'),
    defaultName: defaultName('VID'),
    document: stopCameraVideoRecordingGQL,
    onSaved: () => { recording.value = false; toast(t('recording_saved')) },
    onCancel: () => { recording.value = false },
  })
}

const snap = () => {
  openModal(RecordingSaveModal, {
    title: t('save_photo'),
    defaultName: defaultName('IMG'),
    document: captureCameraPhotoGQL,
    onSaved: () => toast(t('photo_saved')),
  })
}

const goRecordings = () => router.push('/recordings?type=video')

onStartError((e: GqlError) => { toast(t(e.message), 'error'); state.value = 'failed' })
onStopDone(() => { cleanupClient(); state.value = 'idle' })
onStopError((e: GqlError) => toast(t(e.message), 'error'))

const onLiveCameraStreaming = () => {
  if (state.value === 'streaming' || state.value === 'connecting') return
  state.value = 'connecting'; connect()
}

const streaming = computed(() => state.value === 'streaming' || state.value === 'connecting')

watch(videoEl, (el) => {
  if (el && client && state.value !== 'idle') {
    // re-attach if we already have a stream
    el.play().catch(() => {})
  }
})

onMounted(() => {
  emitter.on('webrtc_signaling', onSignaling)
  emitter.on('live_camera_streaming', onLiveCameraStreaming)
  fetchState()
  fetchRecState()
})

onBeforeUnmount(() => {
  emitter.off('webrtc_signaling', onSignaling)
  emitter.off('live_camera_streaming', onLiveCameraStreaming)
  cleanupClient()
})
</script>

<style scoped lang="scss">
.live-monitor { display: flex; flex-direction: column; height: 100%; }
.title { flex: 1; font-weight: 500; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.live-stage { flex: 1; display: flex; align-items: center; justify-content: center; padding: 16px; }
.idle-panel {
  display: flex; flex-direction: column; align-items: center; gap: 12px;
  padding: 32px; border-radius: 16px;
  background: var(--md-sys-color-surface-container);
}
.idle-icon { font-size: 48px; color: var(--md-sys-color-primary); }
.idle-title { font-size: 1.25rem; font-weight: 500; margin: 0; }
.idle-hint { color: var(--md-sys-color-on-surface-variant); margin: 0; text-align: center; }
.facing-row { margin: 8px 0; }
.video-wrap { position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; background: #000; border-radius: 12px; overflow: hidden; }
.live-video { width: 100%; height: 100%; object-fit: contain; background: #000; }
.overlay {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 12px; color: #fff;
  background: rgba(0,0,0,0.4);
}
.spinner { font-size: 32px; }
.error-text { color: var(--md-sys-color-error); margin: 0; }
.rec-overlay {
  position: absolute; left: 12px; right: 12px; bottom: 12px;
  display: flex; align-items: center; justify-content: space-between;
  pointer-events: none;
}
.rec-overlay > * { pointer-events: auto; }
.rec-actions {
  display: flex; gap: 8px;
  background: rgba(0, 0, 0, 0.45); padding: 4px 8px; border-radius: 999px;
  color: #fff;
}
.rec-actions :deep(svg) { color: #fff; }
.rec-icon { color: #fff; }
.rec-icon.stop { color: #fff; }
.rec-badge {
  display: inline-flex; align-items: center; gap: 6px;
  background: rgba(244, 67, 54, 0.9); color: #fff;
  padding: 4px 10px; border-radius: 999px; font-size: 0.75rem; font-weight: 600;
}
.rec-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%;
  background: #fff; animation: blink 1s ease-in-out infinite; }
@keyframes blink { 0%, 100% { opacity: 0.3; } 50% { opacity: 1; } }
</style>
