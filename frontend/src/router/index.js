import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：顶部一条横向模块条（不是左侧树形菜单），
 * 首页就是「发酵罐阵列」，其余四个模块各占一页。
 */
export const navItems = [
  {
    key: 'fermenter',
    path: '/fermenter',
    icon: '🍺',
    label: '发酵罐阵列',
    hint: '纯 CSS 罐体 + 液位 + 阶段标签 + 批次抽屉'
  },
  {
    key: 'batch',
    path: '/batch',
    icon: '📅',
    label: '啤酒批次',
    hint: '计划→糖化→煮沸→发酵→熟成→已灌装 状态推进'
  },
  {
    key: 'recipe',
    path: '/recipe',
    icon: '📖',
    label: '配方与原料用量',
    hint: 'CSS 环形图 + 按投放阶段 Map key 编辑用量'
  },
  {
    key: 'ingredient',
    path: '/ingredient',
    icon: '🌾',
    label: '原料库存',
    hint: '低于安全库存标红 + 入库 / 领用'
  },
  {
    key: 'shipping',
    path: '/shipping',
    icon: '📦',
    label: '灌装与出货',
    hint: '灌装损耗 + 出货金额汇总'
  }
]

const routes = [
  { path: '/', redirect: '/fermenter' },
  { path: '/fermenter', name: 'fermenter', component: () => import('../views/FermenterView.vue') },
  { path: '/batch', name: 'batch', component: () => import('../views/BatchView.vue') },
  { path: '/recipe', name: 'recipe', component: () => import('../views/RecipeView.vue') },
  { path: '/ingredient', name: 'ingredient', component: () => import('../views/IngredientView.vue') },
  { path: '/shipping', name: 'shipping', component: () => import('../views/ShippingView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/fermenter' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
