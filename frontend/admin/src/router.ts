import Vue from "vue";
import Router from "vue-router";
import Layout from "@/layout/index.vue";
import {
  getToken,
  setToken,
  removeToken,
  getStoreId,
  setStoreId,
  removeStoreId,
  setUserInfo,
  getUserInfo,
  removeUserInfo
} from "@/utils/cookies";
import store from "@/store";

Vue.use(Router);

const router = new Router({
  scrollBehavior: (to, from, savedPosition) => {
    if (savedPosition) {
      return savedPosition;
    }
    return { x: 0, y: 0 };
  },
  base: process.env.BASE_URL,
  routes: [
    {
      path: "/login",
      component: () =>
        import(/* webpackChunkName: "login" */ "@/views/login/index.vue"),
      meta: { title: "智餐外卖 · 杭州电子科技大学", hidden: true, notNeedAuth: true }
    },
    {
      path: "/404",
      component: () => import(/* webpackChunkName: "404" */ "@/views/404.vue"),
      meta: { title: "智餐外卖 · 杭州电子科技大学", hidden: true, notNeedAuth: true }
    },
    {
      path: "/",
      component: Layout,
      redirect: "/dashboard",
      children: [
        {
          path: "dashboard",
          component: () =>
            import(/* webpackChunkName: "dashboard" */ "@/views/dashboard/index.vue"),
          name: "Dashboard",
          meta: {
            title: "智餐工作台",
            icon: "dashboard",
            affix: true
          }
        },
		{
          path: "/statistics",
          component: () =>
            import(/* webpackChunkName: "shopTable" */ "@/views/statistics/index.vue"),
          meta: {
            title: "数据中心",
            icon: "icon-statistics"
          }
        },
        {
          path: "order",
          component: () =>
            import(/* webpackChunkName: "shopTable" */ "@/views/orderDetails/index.vue"),
          meta: {
            title: "校园订单",
            icon: "icon-order"
          }
        },
        {
          path: "setmeal",
          component: () =>
            import(/* webpackChunkName: "shopTable" */ "@/views/setmeal/index.vue"),
          meta: {
            title: "套餐管理",
            icon: "icon-combo"
          }
        },
        {
          path: "seckill",
          component: () => import(/* webpackChunkName: "seckill" */ "@/views/seckill/index.vue"),
          meta: { title: "秒杀套餐", icon: "icon-combo" }
        },
        {
          path: "dish",
          component: () =>
            import(/* webpackChunkName: "shopTable" */ "@/views/dish/index.vue"),
          meta: {
            title: "菜品中心",
            icon: "icon-dish"
          }
        },
        {
          path: "/dish/add",
          component: () =>
            import(/* webpackChunkName: "shopTable" */ "@/views/dish/addDishtype.vue"),
          meta: {
            title: "添加菜品",
            hidden: true
          }
        },
        
        {
          path: "category",
          component: () =>
            import(/* webpackChunkName: "shopTable" */ "@/views/category/index.vue"),
          meta: {
            title: "分类管理",
            icon: "icon-category"
          }
        },
        {
          path: "restaurant",
          component: () =>
            import(/* webpackChunkName: "restaurant" */ "@/views/restaurant/index.vue"),
          meta: {
            title: "餐厅管理",
            icon: "icon-restaurant"
          }
        },
        {
          path: "employee",
          component: () =>
            import(/* webpackChunkName: "shopTable" */ "@/views/employee/index.vue"),
          meta: {
            title: "人员管理",
            icon: "icon-employee"
          }
        },
        
        {
          path: "/employee/add",
          component: () =>
            import(/* webpackChunkName: "dashboard" */ "@/views/employee/addEmployee.vue"),
          meta: {
            title: "添加员工",
            hidden: true
          }
        },
        
        {
          path: "/setmeal/add",
          component: () =>
            import(/* webpackChunkName: "shopTable" */ "@/views/setmeal/addSetmeal.vue"),
          meta: {
            title: "添加套餐",
            hidden: true
          }
        }
      ]
    },
    {
      path: "*",
      redirect: "/404",
      meta: { hidden: true }
    }
  ]
});

export default router;
