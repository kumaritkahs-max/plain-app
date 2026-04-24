<template>
  <v-modal @close="cancel">
    <template #headline>
      {{ title }}
    </template>
    <template #content>
      <div class="form-row">
        <v-text-field
          ref="inputRef"
          v-model="name"
          :label="$t('name')"
          :error="!!nameError"
          :error-text="nameError ? $t(nameError) : ''"
          @keyup.enter="onSave"
        />
      </div>
      <div class="form-row">
        <v-text-field v-model="note" :label="$t('note')" />
      </div>
      <div class="form-row">
        <v-text-field
          v-model="tags"
          :label="$t('tags')"
          :placeholder="$t('tags_comma_hint')"
        />
      </div>
    </template>
    <template #actions>
      <v-outlined-button value="cancel" @click="cancel">{{ $t('cancel') }}</v-outlined-button>
      <v-filled-button value="save" :loading="loading" @click="onSave">
        {{ $t('save') }}
      </v-filled-button>
    </template>
  </v-modal>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, type PropType } from 'vue'
import { popModal } from './modal'
import { initMutation } from '@/lib/api/mutation'

interface Props {
  title: string
  defaultName?: string
  document: string
  onSaved?: (item: any) => void
  onCancel?: () => void
}

const props = defineProps({
  title: { type: String, required: true },
  defaultName: { type: String, default: '' },
  document: { type: String, required: true },
  onSaved: { type: Function as PropType<(item: any) => void>, default: () => {} },
  onCancel: { type: Function as PropType<() => void>, default: () => {} },
})

const inputRef = ref<HTMLInputElement>()
const name = ref(props.defaultName)
const note = ref('')
const tags = ref('')
const nameError = ref('')

const { mutate, loading, onDone } = initMutation({ document: props.document })

function cancel() {
  props.onCancel?.()
  popModal()
}

function onSave() {
  if (!name.value?.trim()) { nameError.value = 'valid.required'; return }
  nameError.value = ''
  mutate({ name: name.value.trim(), note: note.value, tags: tags.value })
}

onDone((r: any) => {
  const item = r?.data ? Object.values(r.data)[0] : r
  props.onSaved?.(item)
  popModal()
})

onMounted(async () => {
  await nextTick()
  requestAnimationFrame(() => {
    setTimeout(() => {
      try {
        if (document.activeElement && document.activeElement !== document.body) {
          (document.activeElement as HTMLElement).blur()
        }
        inputRef.value?.focus()
      } catch (e) {
        // ignore
      }
    }, 100)
  })
})
</script>

<style scoped lang="scss">
.form-row { margin-bottom: 12px; }
:deep(.form-control) { width: 100%; }
</style>
