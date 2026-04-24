<template>
  <div class="donut-wrap">
    <svg :viewBox="`0 0 ${size} ${size}`" :width="size" :height="size" class="donut">
      <circle :cx="cx" :cy="cy" :r="radius" fill="none" stroke="var(--md-sys-color-surface-variant)" :stroke-width="thickness" />
      <circle
        v-for="(arc, i) in arcs" :key="i"
        :cx="cx" :cy="cy" :r="radius" fill="none"
        :stroke="arc.color" :stroke-width="thickness"
        :stroke-dasharray="`${arc.length} ${circumference - arc.length}`"
        :stroke-dashoffset="-arc.offset"
        stroke-linecap="butt"
        transform="rotate(-90)"
        :transform-origin="`${cx} ${cy}`"
      />
      <text :x="cx" :y="cy - 4" text-anchor="middle" class="donut-num">{{ data.length }}</text>
      <text :x="cx" :y="cy + 14" text-anchor="middle" class="donut-cap">{{ subLabel }}</text>
    </svg>
    <div class="legend">
      <div v-for="(d, i) in data" :key="d.label" class="legend-row">
        <span class="dot" :style="{ background: colors[i % colors.length] }"></span>
        <span class="lbl">{{ d.label }}</span>
        <span class="val">{{ d.value }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Slice { label: string; value: number }

const props = withDefaults(defineProps<{
  data: Slice[]
  size?: number
  thickness?: number
  subLabel?: string
}>(), { size: 180, thickness: 22, subLabel: '' })

const colors = ['#5a8dee', '#39c5bb', '#f7b500', '#ec5b80', '#a37bf3', '#56c596', '#f08c4e', '#7d97f0']
const cx = computed(() => props.size / 2)
const cy = computed(() => props.size / 2)
const radius = computed(() => (props.size - props.thickness) / 2)
const circumference = computed(() => 2 * Math.PI * radius.value)
const total = computed(() => props.data.reduce((a, b) => a + b.value, 0) || 1)

const arcs = computed(() => {
  let acc = 0
  return props.data.map((d, i) => {
    const length = (d.value / total.value) * circumference.value
    const arc = { length, offset: acc, color: colors[i % colors.length] }
    acc += length
    return arc
  })
})
</script>

<style scoped>
.donut-wrap { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }
.donut text { fill: var(--md-sys-color-on-surface); }
.donut-num { font-size: 22px; font-weight: 700; font-family: var(--md-ref-typeface-plain, sans-serif); }
.donut-cap { font-size: 10px; fill: var(--md-sys-color-on-surface-variant); }
.legend { display: flex; flex-direction: column; gap: 6px; min-width: 160px; flex: 1; }
.legend-row { display: grid; grid-template-columns: 12px 1fr auto; gap: 8px; align-items: center; font-size: 0.825rem; }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.lbl { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.val { color: var(--md-sys-color-on-surface-variant); font-variant-numeric: tabular-nums; }
</style>
