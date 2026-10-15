<template>
  <div class="dashboard-container">
    <PageHeader title="餐厅管理" description="维护餐厅基础信息与启用状态。已有菜单或订单的餐厅请停用，不可直接删除。" />
    <div class="container">
      <div class="tableBar restaurant-toolbar">
        <label>餐厅名称：</label>
        <el-input v-model="keyword" placeholder="请输入餐厅名称" clearable style="width: 210px" @keyup.enter.native="search" @clear="search" />
        <label class="filter-label">状态：</label>
        <el-select v-model="status" placeholder="全部" clearable style="width: 120px" @change="search">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
        <el-button class="normal-btn continue" @click="search">查询</el-button>
        <el-button type="primary" class="add-button" @click="openCreate">+ 新增餐厅</el-button>
      </div>

      <el-table v-if="tableData.length" :data="tableData" stripe class="tableBox">
        <el-table-column prop="code" label="餐厅编码" min-width="130" />
        <el-table-column prop="name" label="餐厅名称" min-width="130" />
        <el-table-column label="所属校区" min-width="120">
          <template slot-scope="scope">{{ campusZoneName(scope.row.campusZoneId) }}</template>
        </el-table-column>
        <el-table-column prop="location" label="位置" min-width="180" show-overflow-tooltip />
        <el-table-column prop="description" label="简介" min-width="180" show-overflow-tooltip />
        <el-table-column label="用餐时段" min-width="180">
          <template slot-scope="scope">{{ mealPeriodLabel(scope.row.mealPeriod) }}</template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="80" />
        <el-table-column label="状态" width="100">
          <template slot-scope="scope">
            <span class="tableColumn-status" :class="{ 'stop-use': Number(scope.row.status) === 0 }">
              {{ Number(scope.row.status) === 1 ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" min-width="170" />
        <el-table-column label="操作" width="190" align="center" fixed="right">
          <template slot-scope="scope">
            <el-button type="text" size="small" class="blueBug" @click="openEdit(scope.row)">编辑</el-button>
            <el-button type="text" size="small" :class="Number(scope.row.status) === 1 ? 'delBut' : 'blueBug'" @click="toggleStatus(scope.row)">
              {{ Number(scope.row.status) === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button type="text" size="small" class="delBut" @click="remove(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Empty v-else :is-search="isSearch" />
      <el-pagination class="pageList" :page-sizes="[10, 20, 30, 40]" :page-size="pageSize" layout="total, sizes, prev, pager, next, jumper" :total="total" @size-change="handleSizeChange" @current-change="handleCurrentChange" />
    </div>

    <el-dialog :title="editing ? '编辑餐厅' : '新增餐厅'" :visible.sync="dialogVisible" width="560px" :close-on-click-modal="false" @closed="resetForm">
      <el-form ref="restaurantForm" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="所属校区" prop="campusZoneId">
          <el-select v-model="form.campusZoneId" placeholder="请选择校区" style="width: 100%">
            <el-option v-for="zone in campusZones" :key="zone.id" :label="zone.name" :value="zone.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="餐厅编码" prop="code"><el-input v-model.trim="form.code" maxlength="32" placeholder="如 CANTEEN_THREE" /></el-form-item>
        <el-form-item label="餐厅名称" prop="name"><el-input v-model.trim="form.name" maxlength="64" placeholder="如 三餐" /></el-form-item>
        <el-form-item label="位置" prop="location"><el-input v-model.trim="form.location" maxlength="128" placeholder="如 下沙校区生活区" /></el-form-item>
        <el-form-item label="简介"><el-input v-model.trim="form.description" type="textarea" :rows="3" maxlength="255" show-word-limit placeholder="可选" /></el-form-item>
        <el-form-item label="用餐时段" prop="mealPeriod">
          <el-select v-model="form.mealPeriod" style="width: 100%">
            <el-option label="三餐（测试环境：00:00-24:00）" value="ALL" />
            <el-option label="早餐（测试环境：00:00-24:00）" value="BREAKFAST" />
            <el-option label="午餐（测试环境：00:00-24:00）" value="LUNCH" />
            <el-option label="晚餐（测试环境：00:00-24:00）" value="DINNER" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序" prop="sort"><el-input-number v-model="form.sort" :min="0" :max="999" :step="1" controls-position="right" /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="submit">保存</el-button></span>
    </el-dialog>
  </div>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-property-decorator'
import Empty from '@/components/Empty/index.vue'
import { addRestaurant, changeRestaurantStatus, deleteRestaurant, editRestaurant, getCampusZones, getRestaurantPage } from '@/api/restaurant'

@Component({ name: 'Restaurant', components: { Empty } })
export default class extends Vue {
  private keyword = ''
  private status: number | null = null
  private page = 1
  private pageSize = 10
  private total = 0
  private tableData: any[] = []
  private campusZones: any[] = []
  private isSearch = false
  private dialogVisible = false
  private editing = false
  private submitting = false
  private form: any = this.emptyForm()

  get rules() {
    return {
      campusZoneId: [{ required: true, message: '请选择所属校区', trigger: 'change' }],
      code: [{ required: true, message: '请输入餐厅编码', trigger: 'blur' }, { pattern: /^[A-Za-z0-9_-]{2,32}$/, message: '编码为 2-32 位字母、数字、下划线或短横线', trigger: 'blur' }],
      name: [{ required: true, message: '请输入餐厅名称', trigger: 'blur' }],
      location: [{ required: true, message: '请输入餐厅位置', trigger: 'blur' }],
      mealPeriod: [{ required: true, message: '请选择用餐时段', trigger: 'change' }],
      sort: [{ required: true, message: '请输入排序', trigger: 'change' }]
    }
  }

  created() {
    this.loadCampusZones()
    this.loadData()
  }

  private emptyForm() { return { id: null, campusZoneId: null, code: '', name: '', location: '', description: '', mealPeriod: 'ALL', sort: 0 } }

  private async loadCampusZones() {
    try {
      const res: any = await getCampusZones()
      if (res.data.code === 1) this.campusZones = res.data.data || []
    } catch (err) {
      this.$message.error('校区列表加载失败')
    }
  }

  private async loadData() {
    try {
      const res: any = await getRestaurantPage({ page: this.page, pageSize: this.pageSize, name: this.keyword || undefined, status: this.status })
      if (res.data.code === 1) {
        this.tableData = res.data.data.records || []
        this.total = Number(res.data.data.total || 0)
      } else this.$message.error(res.data.msg || '餐厅列表加载失败')
    } catch (err) {
      this.$message.error('餐厅列表加载失败')
    }
  }

  private search() { this.page = 1; this.isSearch = true; this.loadData() }
  private campusZoneName(id: number) { const zone = this.campusZones.find(item => Number(item.id) === Number(id)); return zone ? zone.name : `校区 #${id}` }
  private handleSizeChange(size: number) { this.pageSize = size; this.page = 1; this.loadData() }
  private handleCurrentChange(page: number) { this.page = page; this.loadData() }

  private openCreate() {
    this.editing = false
    this.form = this.emptyForm()
    if (this.campusZones.length === 1) this.form.campusZoneId = this.campusZones[0].id
    this.dialogVisible = true
  }

  private openEdit(row: any) { this.editing = true; this.form = { id: row.id, campusZoneId: row.campusZoneId, code: row.code, name: row.name, location: row.location, description: row.description || '', mealPeriod: row.mealPeriod || 'ALL', sort: row.sort }; this.dialogVisible = true }
  private mealPeriodLabel(value: string) {
    const labels: any = { ALL: '三餐：00:00-24:00', BREAKFAST: '早餐：00:00-24:00', LUNCH: '午餐：00:00-24:00', DINNER: '晚餐：00:00-24:00' }
    return labels[value || 'ALL']
  }
  private resetForm() { const form: any = this.$refs.restaurantForm; if (form) form.clearValidate(); this.form = this.emptyForm() }

  private submit() {
    const form: any = this.$refs.restaurantForm
    form.validate(async (valid: boolean) => {
      if (!valid) return
      this.submitting = true
      try {
        const res: any = await (this.editing ? editRestaurant(this.form) : addRestaurant(this.form))
        if (res.data.code !== 1) throw new Error(res.data.msg || '保存失败')
        this.$message.success(this.editing ? '餐厅已更新' : '餐厅已新增')
        this.dialogVisible = false
        this.loadData()
      } catch (err) {
        this.$message.error((err as any).message || '保存失败')
      } finally { this.submitting = false }
    })
  }

  private toggleStatus(row: any) {
    const nextStatus = Number(row.status) === 1 ? 0 : 1
    this.$confirm(`确认${nextStatus === 1 ? '启用' : '停用'}“${row.name}”吗？`, '调整餐厅状态', { type: 'warning' }).then(async () => {
      try {
        const res: any = await changeRestaurantStatus(row.id, nextStatus)
        if (res.data.code !== 1) throw new Error(res.data.msg || '状态调整失败')
        this.$message.success(nextStatus === 1 ? '餐厅已启用' : '餐厅已停用')
        this.loadData()
      } catch (err) { this.$message.error((err as any).message || '状态调整失败') }
    }).catch(() => undefined)
  }

  private remove(row: any) {
    this.$confirm(`确认删除“${row.name}”吗？已有菜单或订单的餐厅不能删除，请改为停用。`, '删除餐厅', { type: 'warning', confirmButtonText: '删除' }).then(async () => {
      try {
        const res: any = await deleteRestaurant(row.id)
        if (res.data.code !== 1) throw new Error(res.data.msg || '删除失败')
        this.$message.success('餐厅已删除')
        if (this.tableData.length === 1 && this.page > 1) this.page -= 1
        this.loadData()
      } catch (err) { this.$message.error((err as any).message || '删除失败') }
    }).catch(() => undefined)
  }

  $refs!: { restaurantForm: any }
}
</script>

<style lang="scss" scoped>
.restaurant-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.filter-label { margin-left: 12px; }
.add-button { margin-left: auto; }
</style>
