package com.example.westcon.fragments

import android.os.Bundle
import androidx.compose.runtime.Composable
import com.example.westcon.ui.screens.SignUpStepTwoScreen

class SignUpStepTwoFragment : BaseFragment() {
    
    companion object {
        private const val ARG_EMAIL = "email"

        fun newInstance(email: String): SignUpStepTwoFragment {
            val fragment = SignUpStepTwoFragment()
            val args = Bundle()
            args.putString(ARG_EMAIL, email)
            fragment.arguments = args
            return fragment
        }
    }

    @Composable
    override fun ScreenContent() {
        val email = arguments?.getString(ARG_EMAIL) ?: ""
        
        SignUpStepTwoScreen(
            email = email,
            onNextClick = { clearBackStackAndNavigate(DashboardFragment()) }
        )
    }
}
