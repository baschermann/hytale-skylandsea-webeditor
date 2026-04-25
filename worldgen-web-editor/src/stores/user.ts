//import { ref, computed } from 'vue'
import { ref } from 'vue'
import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', () => {
  const isLoggedIn = ref(false)

  function logIn() {
    isLoggedIn.value = true;
  }

  function logOut() {
    isLoggedIn.value = false;
  }

  return { isLoggedIn, logIn, logOut }
})
