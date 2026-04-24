<template>
  <v-modal @close="cancel">
    <template #headline>{{ $t('time_limit_for') }} {{ appName }}</template>
    <template #content>
      <p class="muted">{{ $t('time_limit_help') }}</p>
      <div class="row">
        <v-text-field v-model.number="hours" type="number" min="0" max="23" :label="$t('hours')" />
        <v-text-field v-model.number="minutes" type="number" min="0" max="59" :label="$t('minutes')" />
      </div>
      <div class="muted">{{ $t('time_limit_hint') }}</div>
    </template>
    <template #actions>
      <v-outlined-button v-if="initialMs > 0" @click="clear">{{ $t('clear') }}</v-outlined-button>
      <v-outlined-button @click="cancel">{{ $t('cancel') }}</v-outlined-button>
      <v-filled-button :loading="loading" @click="save">{{ $t('save') }}</v-filled-button>
    </template>
  </v-modal>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { popModal } from './modal'
import { initMutation, setAppTimeLimitGQL } from '@/lib/api/mutation'

const props = defineProps<{
  packageId: string
  appName: string
  initialMs: number
  done?: (ms: number) => void
}>()

const totalMin = Math.max(0, Math.floor(props.initialMs / 60000))
const hours = ref(Math.floor(totalMin / 60))
const minutes = ref(totalMin % 60)

const { mutate, loading } = initMutation({ document: setAppTimeLimitGQL })

function cancel() { popModal() }
function save() {
  const ms = ((hours.value || 0) * 60 + (minutes.value || 0)) * 60000
  mutate({ packageId: props.packageId, dailyMs: ms }).then((r) => {
    if (r) { props.done?.(ms); popModal() }
  })
}
function clear() {
  mutate({ packageId: props.packageId, dailyMs: 0 }).then((r) => {
    if (r) { props.done?.(0); popModal() }
  })
}
</script>

<style scoped>
.row { display: flex; gap: 12px; }
.muted { color: var(--md-sys-color-on-surface-variant); font-size: 0.875rem; margin: 4px 0 12px; }
</style>
