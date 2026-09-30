<template>
  <div>
    <div class="panel">
      <div class="panel-title">
        配方与原料用量
        <span class="hint">
          环形图为各类原料在【同一计量维度内】的投放占比；明细保留酿酒师习惯单位，合计统一换算到 g / mL / pkg，质量与体积不相加
        </span>
      </div>
      <div class="toolbar">
        <span class="spacer"></span>
        <el-button type="primary" @click="createVisible = true">新增配方</el-button>
      </div>

      <div class="card-wall">
        <div v-for="r in recipes" :key="r.id" class="recipe-card">
          <div class="recipe-card-head">
            <div class="ring" :style="{ background: ringGradient(r) }">
              <div class="ring-label">{{ r.itemCount }}<br />阶段</div>
            </div>
            <div style="flex: 1">
              <div style="font-size: 15px; font-weight: 700">
                {{ r.name }}
                <el-tag v-if="r.reviewCount > 0" type="danger" size="small" style="margin-left: 6px">
                  {{ r.reviewCount }} 条单位待处理
                </el-tag>
              </div>
              <div style="font-size: 12px; color: #6b7760">
                {{ r.code }} · {{ r.styleName }} · 基准批量 {{ fmt(r.baseBatchL) }} L
              </div>
              <div style="font-size: 12px; margin-top: 4px">
                OG {{ r.targetOg }} · {{ r.targetAbv }}% · {{ r.targetIbu }} IBU · {{ r.targetEbc }} EBC
              </div>
              <!-- 规范化合计：质量 / 体积 / 计数 分列 -->
              <div class="norm-totals">
                <span v-for="t in r.totals" :key="t.dimension" class="norm-chip">
                  {{ t.dimensionName }} {{ fmt(t.amount) }} {{ t.baseUnit }}
                </span>
                <span v-if="!r.totals.length" class="norm-chip dim-none">暂无可汇总明细</span>
              </div>
            </div>
          </div>
          <div class="legend">
            <span v-for="s in r.composition" :key="s.ingredientType" :title="`${fmt(s.amount)} ${s.baseUnit}`">
              <i :style="{ background: s.color }"></i>{{ s.ingredientTypeName }} {{ s.percent }}%
            </span>
            <span class="legend-dim">（占比口径：{{ r.primaryDimensionName }} · 合计 {{ primaryTotal(r) }} {{ r.primaryBaseUnit }}）</span>
          </div>
          <div style="margin-top: 10px; display: flex; gap: 8px; align-items: center">
            <el-button size="small" type="primary" plain @click="openEdit(r)">编辑用量（Map）</el-button>
            <el-button size="small" type="warning" plain @click="openScale(r)">配方缩放</el-button>
            <span style="font-size: 12px; color: #6b7760">{{ r.processNote }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Map 编辑：按投放阶段（Map 的 key）增删改用量 -->
    <el-drawer v-model="drawer" :title="current ? current.name + ' · 原料用量' : '用量'" size="720px">
      <el-alert v-if="current && current.reviewCount > 0" type="error" :closable="false" show-icon
                style="margin-bottom: 10px"
                :title="`有 ${current.reviewCount} 条明细的单位无法可靠换算，已保留原值但不参与合计/缩放，请在下表确认单位`" />
      <el-alert v-if="current && mismatchCount > 0" type="warning" :closable="false" show-icon
                style="margin-bottom: 10px"
                :title="`有 ${mismatchCount} 条明细的单位维度与原料档案不一致（如该按质量的填成了体积），已按所填单位计算并标黄，请核对`" />

      <el-table v-if="current" :data="current.items" size="small" border>
        <el-table-column prop="stageName" label="投放阶段（key）" width="130">
          <template #default="{ row }">
            <el-tag size="small" type="success">{{ row.stage }}</el-tag>
            <div style="font-size: 11px; color: #6b7760">{{ row.stageName }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="ingredientName" label="原料" min-width="110" />
        <el-table-column prop="ingredientTypeName" label="类别" width="70" />
        <el-table-column label="用量（显示单位）" width="120">
          <template #default="{ row }">
            <span :class="{ 'row-danger': row.needsReview }">{{ row.quantity }} {{ row.unit || '（空）' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="规范化" width="120">
          <template #default="{ row }">
            <el-tooltip v-if="row.unitNote" :content="row.unitNote" placement="top" :show-after="100">
              <span :class="row.needsReview ? 'row-danger' : (row.dimMismatch ? 'unit-mismatch' : 'unit-ok')">
                {{ row.needsReview ? '待处理' : fmt(row.normalizedQuantity) + ' ' + row.dimensionName }}
              </span>
            </el-tooltip>
            <span v-else :class="row.needsReview ? 'row-danger' : 'unit-ok'">
              {{ row.needsReview ? '待处理' : fmt(row.normalizedQuantity) + ' ' + row.dimensionName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" plain @click="fillForm(row)">修改</el-button>
            <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 阶段小计（质量/体积分列） -->
      <el-divider content-position="left">阶段小计（规范化）</el-divider>
      <el-table v-if="current" :data="current.stageTotals" size="small" border>
        <el-table-column prop="stageName" label="投放阶段" width="150" />
        <el-table-column label="质量 g">
          <template #default="{ row }">{{ cellText(row.byDimension, 'MASS') }}</template>
        </el-table-column>
        <el-table-column label="体积 mL">
          <template #default="{ row }">{{ cellText(row.byDimension, 'VOLUME') }}</template>
        </el-table-column>
        <el-table-column label="计数 pkg">
          <template #default="{ row }">{{ cellText(row.byDimension, 'COUNT') }}</template>
        </el-table-column>
      </el-table>

      <!-- 整张配方规范化合计 -->
      <div class="drawer-total">
        配方规范化合计：
        <b v-for="t in (current ? current.totals : [])" :key="t.dimension" style="margin-right: 14px">
          {{ t.dimensionName }} {{ fmt(t.amount) }} {{ t.baseUnit }}
        </b>
      </div>

      <el-divider content-position="left">新增 / 修改某个 key 的用量</el-divider>
      <el-form :model="itemForm" label-width="96px">
        <el-form-item label="投放阶段">
          <el-select v-model="itemForm.stage" filterable allow-create style="width: 100%">
            <el-option v-for="s in stages" :key="s.key" :label="s.name + '（' + s.key + '）'" :value="s.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="原料">
          <el-select v-model="itemForm.ingredientCode" filterable style="width: 100%" @change="onPickIngredient">
            <el-option v-for="i in ingredients" :key="i.code"
                       :label="i.name + ' · ' + i.code + '（档案单位 ' + i.unit + '）'" :value="i.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="用量">
          <el-input-number v-model="itemForm.quantity" :precision="3" :min="0.001" :step="0.5" style="width: 55%" />
          <el-select v-model="itemForm.unit" style="width: 40%; margin-left: 5%" placeholder="留空=档案单位">
            <el-option label="千克 kg（质量）" value="kg" />
            <el-option label="克 g（质量）" value="g" />
            <el-option label="升 L（体积）" value="L" />
            <el-option label="毫升 mL（体积）" value="mL" />
            <el-option label="包 pkg（计数）" value="pkg" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="itemForm.note" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submitItem">保存（无此 key 则新增）</el-button>
          <el-button @click="resetForm">清空</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>

    <!-- 配方缩放：基准批量 + 目标批量 + 预览 + 确认 -->
    <el-dialog v-model="scaleVisible" :title="`配方缩放 · ${scale.recipeName || ''}`" width="860px" append-to-body>
      <div style="display: flex; gap: 16px; align-items: center; flex-wrap: wrap">
        <div>
          基准批量
          <el-input-number v-model="scale.baseBatchL" :min="1" :precision="0" style="width: 130px; margin-left: 6px" /> L
        </div>
        <div>
          目标批量
          <el-input-number v-model="scale.targetBatchL" :min="1" :precision="0" style="width: 130px; margin-left: 6px" /> L
        </div>
        <el-button type="primary" plain :loading="scale.previewing" @click="doPreview">生成预览</el-button>
        <el-button text @click="saveBaseBatch">保存基准批量</el-button>
      </div>

      <el-alert v-if="scale.preview" :closable="false" show-icon style="margin: 10px 0"
        :type="scale.preview.reviewCount > 0 ? 'error' : 'info'"
        :title="scale.preview.reviewCount > 0
          ? `有 ${scale.preview.reviewCount} 条单位待处理明细未参与缩放，请先在用量明细里修正单位`
          : `缩放比例 ${scale.preview.factor}（目标/基准）；逐条在 g/mL/pkg 上取整，余差按最大余数法分配，合计对齐目标`" />

      <el-table v-if="scale.preview" :data="scale.preview.lines" size="small" border max-height="360">
        <el-table-column prop="stageName" label="阶段" width="92" />
        <el-table-column prop="ingredientName" label="原料" min-width="100" />
        <el-table-column label="原值" width="105">
          <template #default="{ row }">{{ fmt(row.originalQuantity) }} {{ row.originalUnit }}</template>
        </el-table-column>
        <el-table-column label="换算值(基准)" width="110">
          <template #default="{ row }">{{ fmt(row.originalBase) }} {{ baseUnit(row.dimension) }}</template>
        </el-table-column>
        <el-table-column label="精确比例值" width="105">
          <template #default="{ row }">{{ fmt(row.exactBase) }} {{ baseUnit(row.dimension) }}</template>
        </el-table-column>
        <el-table-column label="调整后(基准)" width="110">
          <template #default="{ row }">
            <b>{{ fmt(row.adjustedBase) }}</b> {{ baseUnit(row.dimension) }}
          </template>
        </el-table-column>
        <el-table-column label="调整后显示" width="105">
          <template #default="{ row }">{{ fmt(row.adjustedQuantity) }} {{ row.adjustedUnit }}</template>
        </el-table-column>
        <el-table-column label="余差去向" min-width="120">
          <template #default="{ row }">
            <el-tag v-if="Number(row.remainderDelta) > 0" size="small" type="warning">+{{ row.remainderDelta }}</el-tag>
            <el-tag v-else-if="Number(row.remainderDelta) < 0" size="small" type="info">{{ row.remainderDelta }}</el-tag>
            <span v-else style="color: #909399; font-size: 12px">—</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="scale.preview" style="margin-top: 8px; font-size: 12px">
        <div v-for="d in scale.preview.dimensions" :key="d.dimension" style="margin-bottom: 2px">
          {{ d.dimensionName }}：原合计 {{ fmt(d.originalTotal) }} {{ d.baseUnit }}
          → 目标 {{ fmt(d.targetTotal) }} → 调整后 <b>{{ fmt(d.adjustedTotal) }}</b> {{ d.baseUnit }}
          <span v-if="Number(d.leftover) !== 0" style="color: #c0392b">
            （受最小投放量限制，剩余 {{ fmt(d.leftover) }} {{ d.baseUnit }} 无法分配）
          </span>
        </div>
      </div>

      <template #footer>
        <el-button @click="scaleVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!canApply" :loading="scale.applying" @click="doApply">
          确认应用到配方
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新增配方" width="520px">
      <el-form :model="createForm" label-width="96px">
        <el-form-item label="配方编码"><el-input v-model="createForm.code" placeholder="如 R-1011" /></el-form-item>
        <el-form-item label="配方名"><el-input v-model="createForm.name" /></el-form-item>
        <el-form-item label="风格">
          <el-select v-model="createForm.style" style="width: 100%">
            <el-option label="IPA" value="IPA" />
            <el-option label="世涛" value="STOUT" />
            <el-option label="皮尔森" value="PILSNER" />
            <el-option label="小麦" value="WHEAT" />
            <el-option label="酸啤" value="SOUR" />
            <el-option label="淡色艾尔" value="PALE_ALE" />
          </el-select>
        </el-form-item>
        <el-form-item label="基准批量(L)">
          <el-input-number v-model="createForm.baseBatchL" :min="1" :precision="0" />
        </el-form-item>
        <el-form-item label="目标原浓"><el-input-number v-model="createForm.targetOg" :precision="3" :step="0.001" /></el-form-item>
        <el-form-item label="目标酒精度"><el-input-number v-model="createForm.targetAbv" :precision="2" :step="0.1" /></el-form-item>
        <el-form-item label="目标苦度"><el-input-number v-model="createForm.targetIbu" :precision="1" :step="1" /></el-form-item>
        <el-form-item label="目标色度"><el-input-number v-model="createForm.targetEbc" :precision="1" :step="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ingredientApi, recipeApi } from '../api'

const recipes = ref([])
const stages = ref([])
const ingredients = ref([])
const drawer = ref(false)
const current = ref(null)
const createVisible = ref(false)
const createForm = ref({
  code: '', name: '', style: 'IPA', baseBatchL: 1000,
  targetOg: 1.05, targetAbv: 5, targetIbu: 30, targetEbc: 10
})
const itemForm = ref({ stage: 'MASH_IN', ingredientCode: '', quantity: 1, unit: 'kg', note: '' })

// 缩放弹窗状态
const scaleVisible = ref(false)
const scale = ref({
  recipeId: null, recipeName: '', baseBatchL: 1000, targetBatchL: 1000,
  preview: null, previewing: false, applying: false
})

const DIM_BASE = { MASS: 'g', VOLUME: 'mL', COUNT: 'pkg' }
function baseUnit(dim) { return DIM_BASE[dim] || '' }
function primaryTotal(recipe) {
  const c = (recipe.totals || []).find((t) => t.dimension === recipe.primaryDimension)
  return c ? fmt(c.amount) : '0'
}
function fmt(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(3)))
}
function cellText(cells, dim) {
  const c = (cells || []).find((x) => x.dimension === dim)
  return c ? fmt(c.amount) : '—'
}

const canApply = computed(() =>
  scale.value.preview && scale.value.preview.reviewCount === 0)

const mismatchCount = computed(() =>
  (current.value?.items || []).filter((i) => i.dimMismatch).length)

/** CSS conic-gradient 环形图：主维度内各类型占比 */
function ringGradient(recipe) {
  const slices = recipe.composition || []
  if (!slices.length) return '#e3e8d9'
  let acc = 0
  const parts = slices.map((s) => {
    const from = acc
    acc += Number(s.percent)
    return `${s.color} ${from}% ${acc}%`
  })
  return `conic-gradient(${parts.join(', ')})`
}

async function load() {
  recipes.value = await recipeApi.list()
  stages.value = await recipeApi.stages()
  ingredients.value = await ingredientApi.list()
  // 抽屉开着时同步当前配方，保证缩放/改明细后版本号是最新的
  if (current.value) {
    current.value = recipes.value.find((r) => r.id === current.value.id) || current.value
  }
}

async function openEdit(recipe) {
  current.value = await recipeApi.get(recipe.id)
  resetForm()
  drawer.value = true
}

function resetForm() {
  itemForm.value = { stage: 'MASH_IN', ingredientCode: '', quantity: 1, unit: 'kg', note: '' }
}

function onPickIngredient(code) {
  const ing = ingredients.value.find((i) => i.code === code)
  if (ing) itemForm.value.unit = ing.unit === 'L' || ing.unit === 'mL' ? ing.unit : (ing.unit === 'pkg' ? 'pkg' : 'kg')
}

function fillForm(row) {
  itemForm.value = {
    stage: row.stage,
    ingredientCode: row.ingredientCode,
    quantity: Number(row.quantity),
    unit: row.normalizedUnit || row.unit || 'kg',
    note: row.note
  }
}

async function submitItem() {
  if (!itemForm.value.ingredientCode) {
    ElMessage.warning('请选择原料')
    return
  }
  const body = {
    ingredientCode: itemForm.value.ingredientCode,
    quantity: itemForm.value.quantity,
    unit: itemForm.value.unit || null,
    note: itemForm.value.note
  }
  await recipeApi.putItem(current.value.id, itemForm.value.stage, body)
  ElMessage.success('用量已保存')
  current.value = await recipeApi.get(current.value.id)
  await load()
}

async function remove(row) {
  await recipeApi.removeItem(current.value.id, row.stage)
  ElMessage.success('该阶段用量已删除')
  current.value = await recipeApi.get(current.value.id)
  await load()
}

async function submitCreate() {
  await recipeApi.create(createForm.value)
  ElMessage.success('配方已新建')
  createVisible.value = false
  await load()
}

// ---------------- 配方缩放 ----------------
async function openScale(recipe) {
  const full = await recipeApi.get(recipe.id)
  scale.value = {
    recipeId: full.id,
    recipeName: full.name,
    baseBatchL: Number(full.baseBatchL || 1000),
    targetBatchL: Number(full.baseBatchL || 1000),
    preview: null,
    previewing: false,
    applying: false
  }
  scaleVisible.value = true
}

async function saveBaseBatch() {
  await recipeApi.updateBaseBatch(scale.value.recipeId, scale.value.baseBatchL)
  ElMessage.success('基准批量已保存')
  await load()
}

async function doPreview() {
  scale.value.previewing = true
  try {
    scale.value.preview = await recipeApi.scalePreview(scale.value.recipeId, scale.value.targetBatchL)
  } finally {
    scale.value.previewing = false
  }
}

async function doApply() {
  const pv = scale.value.preview
  scale.value.applying = true
  try {
    await ElMessageBox.confirm(
      `将把配方明细按目标批量 ${scale.value.targetBatchL} L 成比例调整（比例 ${pv.factor}），确认应用？`,
      '确认应用缩放', { type: 'warning', confirmButtonText: '确认应用', cancelButtonText: '取消' })
  } catch (e) { scale.value.applying = false; return }
  try {
    await recipeApi.scaleApply(scale.value.recipeId, {
      targetBatchL: scale.value.targetBatchL,
      baseVersion: pv.baseVersion
    })
    ElMessage.success('配方已缩放，规范化合计已对齐目标批量')
    scaleVisible.value = false
    if (current.value && current.value.id === scale.value.recipeId) {
      current.value = await recipeApi.get(scale.value.recipeId)
    }
    await load()
  } catch (e) {
    // 409 过期：旧预览作废、同步最新版本，提示重新生成，绝不把旧结果覆盖到新数据上
    if (e?.response?.status === 409) {
      scale.value.preview = null
      ElMessage.warning('缩放结果已过期（明细刚被修改），已为你刷新，请重新生成预览后再应用')
      try {
        const fresh = await recipeApi.get(scale.value.recipeId)
        scale.value.baseBatchL = Number(fresh.baseBatchL || 1000)
      } catch (_) { /* 忽略刷新失败 */ }
    }
  } finally {
    scale.value.applying = false
  }
}

onMounted(load)
</script>

<style scoped>
.norm-totals { margin-top: 6px; display: flex; gap: 6px; flex-wrap: wrap; }
.norm-chip {
  font-size: 11px; background: #eef3e6; color: #44523a; border-radius: 8px;
  padding: 1px 8px;
}
.norm-chip.dim-none { background: #f2f3f5; color: #909399; }
.legend-dim { color: #909399; font-size: 11px; }
.drawer-total { margin-top: 10px; font-size: 13px; color: #44523a; }
.unit-ok { color: #44523a; }
.unit-mismatch { color: #b88230; font-weight: 600; border-bottom: 1px dashed #b88230; cursor: help; }
.unit-normalized { color: #b88230; border-bottom: 1px dashed #b88230; cursor: help; }
.unit-inferred { color: #2f6fb0; border-bottom: 1px dashed #2f6fb0; cursor: help; }
.row-danger { color: #c0392b; font-weight: 600; }
</style>
