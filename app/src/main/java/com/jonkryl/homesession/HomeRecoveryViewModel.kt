package com.jonkryl.homesession

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.jonkryl.homesession.core.HomeRepository
import java.util.concurrent.Executors

/** The approved file operation survives rotation and never retains an Activity. */
class HomeRecoveryViewModel(application: Application) : AndroidViewModel(application) {
    enum class Status { IDLE, COPYING, COMPLETE, FAILED }
    private val worker = Executors.newSingleThreadExecutor()
    private val mutableStatus = MutableLiveData(Status.IDLE)
    val status: LiveData<Status> = mutableStatus

    fun restartEmpty() {
        if (mutableStatus.value == Status.COPYING || mutableStatus.value == Status.COMPLETE) return
        mutableStatus.value = Status.COPYING
        worker.execute {
            val result = try {
                HomeRepository.restartEmpty(getApplication())
                Status.COMPLETE
            } catch (_: Exception) {
                Status.FAILED
            }
            mutableStatus.postValue(result)
        }
    }

    /** Consume before recreating: a later load failure must stay on the recovery screen. */
    fun consumeCompletion(): Boolean {
        if (mutableStatus.value != Status.COMPLETE) return false
        mutableStatus.value = Status.IDLE
        return true
    }

    override fun onCleared() {
        // An approved operation may finish without a visible Activity; its durable result is safe.
        worker.shutdown()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == HomeRecoveryViewModel::class.java)
            return modelClass.cast(HomeRecoveryViewModel(application))
        }
    }
}
