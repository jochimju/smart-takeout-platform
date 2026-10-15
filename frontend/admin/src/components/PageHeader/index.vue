<template>
  <header class="zc-page-header">
    <div>
      <span class="zc-page-kicker">校园餐饮</span>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.default" class="zc-page-header__actions">
      <slot />
    </div>
  </header>
</template>

<script lang="ts">
import { Component, Prop, Vue } from 'vue-property-decorator'

@Component({ name: 'PageHeader' })
export default class extends Vue {
  @Prop({ required: true }) private title!: string
  @Prop({ default: '' }) private description!: string
}
</script>
