<template>
  <div v-bind:data-per-path="model.path">
    <header class="usa-header usa-header-extended" role="banner">{% render '@site-title', {header: header}, true %}
      <nav role="navigation"
      class="usa-nav">
        <div class="usa-nav-inner">{% render '@nav-primary', {nav: header.primary}, true %} {%
          render '@nav-secondary', {nav: header.secondary, id_prefix: header.primary.id_prefix
          }, true %}</div>
      </nav>
    </header>
    <div class="usa-overlay"></div>
  </div>
</template>

<script>
    export default {
        props: ['model']
    }
</script>

