<template>
  <div>
    <div class="panel">
      <div class="panel-title">
        原料库存
        <span class="hint">当前库存低于安全库存的行整行标红</span>
      </div>

      <div class="summary-strip">
        <div class="summary-box"><b>{{ rows.length }}</b><span>原料档案数</span></div>
        <div class="summary-box">
          <b :style="{ color: lowCount > 0 ? '#c0392b' : undefined }">{{ lowCount }}</b><span>低于安全库存</span>
        </div>
        <div class="summary-box"><b>{{ totalValue }}</b><span>库存货值（元）</span></div>
        <div class="summary-box"><b>{{ soonExpire }}</b><span>90 天内到期</span></div>
      </div>

      <div class="toolbar">
        <el-select v-model="typeFilter" clearable placeholder="按类型筛选" style="width: 170px" @change="load">
          <el-option label="麦芽" value="MALT" />
          <el-option label="啤酒花" value="HOP" />
          <el-option label="酵母" value="YEAST" />
          <el-option label="辅料" value="ADJUNCT" />
          <el-option label="水处理剂" value="WATER" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="createVisible = true">新增原料</el-button>
      </div>

      <el-table :data="rows" border stripe size="small" :row-class-name="rowClass">
        <el-table-column prop="code" label="原料编号" width="120" />
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column prop="typeName" label="类型" width="90" />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column label="当前库存" width="110">
          <template #default="{ row }">
            <span :class="{ 'row-danger': row.low }">{{ row.stock }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="safetyStock" label="安全库存" width="100" />
        <el-table-column prop="unitPrice" label="单价" width="90" />
        <el-table-column prop="supplier" label="供应商" min-width="130" />
        <el-table-column prop="shelfLifeTo" label="保质期至" width="110" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" type="success" plain @click="openStock(row, 'IN')">入库</el-button>
            <el-button size="small" type="warning" plain @click="openStock(row, 'OUT')">领用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="stockVisible" :title="`${stockRow.name || ''} · ${stockForm.mode === 'IN' ? '入库' : '领用扣减'}`" width="380px">
      <el-form :model="stockForm" label-width="80px">
        <el-form-item label="数量">
          <el-input-number v-model="stockForm.quantity" :precision="3" :min="0.001" :step="10" style="width: 100%" />
        </el-form-item>
        <el-form-item label="模式">
          <el-radio-group v-model="stockForm.mode">
            <el-radio label="IN">入库</el-radio>
            <el-radio label="OUT">领用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stockVisible = false">取消</el-button>
        <el-button type="primary" @click="submitStock">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新增原料" width="520px">
      <el-form :model="createForm" label-width="96px">
        <el-form-item label="原料编号"><el-input v-model="createForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="createForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="createForm.type" style="width: 100%">
            <el-option label="麦芽" value="MALT" />
            <el-option label="啤酒花" value="HOP" />
            <el-option label="酵母" value="YEAST" />
            <el-option label="辅料" value="ADJUNCT" />
            <el-option label="水处理剂" value="WATER" />
          </el-select>
        </el-form-item>
        <el-form-item label="单位"><el-input v-model="createForm.unit" /></el-form-item>
        <el-form-item label="当前库存"><el-input-number v-model="createForm.stock" :precision="3" :min="0" /></el-form-item>
        <el-form-item label="安全库存"><el-input-number v-model="createForm.safetyStock" :precision="3" :min="0" /></el-form-item>
        <el-form-item label="单价"><el-input-number v-model="createForm.unitPrice" :precision="2" :min="0" /></el-form-item>
        <el-form-item label="供应商"><el-input v-model="createForm.supplier" /></el-form-item>
        <el-form-item label="保质期至">
          <el-date-picker v-model="createForm.shelfLifeTo" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
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
import { ElMessage } from 'element-plus'
import { ingredientApi } from '../api'

const rows = ref([])
const typeFilter = ref('')
const stockVisible = ref(false)
const createVisible = ref(false)
const stockRow = ref({})
const stockForm = ref({ quantity: 10, mode: 'IN' })
const createForm = ref({
  code: '', name: '', type: 'MALT', unit: 'kg',
  stock: 0, safetyStock: 0, unitPrice: 0, supplier: '', shelfLifeTo: ''
})

const lowCount = computed(() => rows.value.filter((r) => r.low).length)

const totalValue = computed(() => {
  const sum = rows.value.reduce((acc, r) => acc + Number(r.stock || 0) * Number(r.unitPrice || 0), 0)
  return sum.toFixed(0)
})

const soonExpire = computed(() => {
  const limit = new Date()
  limit.setDate(limit.getDate() + 90)
  return rows.value.filter((r) => r.shelfLifeTo && new Date(r.shelfLifeTo) <= limit).length
})

function rowClass({ row }) {
  return row.low ? 'low-row' : ''
}

async function load() {
  rows.value = await ingredientApi.list(typeFilter.value)
}

function openStock(row, mode) {
  stockRow.value = row
  stockForm.value = { quantity: 10, mode }
  stockVisible.value = true
}

async function submitStock() {
  await ingredientApi.stock(stockRow.value.id, stockForm.value.quantity, stockForm.value.mode)
  ElMessage.success('库存已更新')
  stockVisible.value = false
  await load()
}

async function submitCreate() {
  await ingredientApi.create(createForm.value)
  ElMessage.success('原料已建档')
  createVisible.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
:deep(.low-row) {
  background: #fdecea !important;
}
</style>
