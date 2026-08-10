package dev.reactant.reactant.ui

import dev.reactant.reactant.service.spec.server.SchedulerService
import dev.reactant.reactant.ui.event.UIEvent
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable

/**
 * A UI object that have its own scheduler and event bus
 * Those scheduler and event subscription will be destroyed when the object destroy
 */
interface UIDestroyable {
    val compositeDisposable: CompositeDisposable
    val scheduler: SchedulerService
    val event: Observable<out UIEvent>
    fun destroy()

    fun <T : Any> subscribe(observable: Observable<T>, onNext: (T) -> Unit): Disposable =
            observable.doOnDispose { compositeDisposable.isDisposed }
                    .subscribe(onNext)
                    .also { compositeDisposable.add(it) }

    companion object {
        @JvmStatic
        fun convertToDestroyableScheduler(destroyable: UIDestroyable, schedulerService: SchedulerService): SchedulerService {
            return DestroyableScheduler(schedulerService, destroyable.compositeDisposable)
        }

        private class DestroyableScheduler(val schedulerService: SchedulerService, val compositeDisposable: CompositeDisposable) : SchedulerService {
            override fun next(): Completable = schedulerService.next().doOnSubscribe { compositeDisposable.add(it) }

            override fun timer(delay: Long): Completable = schedulerService.timer(delay).doOnSubscribe { compositeDisposable.add(it) }

            override fun interval(delay: Long, period: Long): Observable<Int> = schedulerService.interval(delay, period)
                    .doOnSubscribe { compositeDisposable.add(it) }

            override val mainThreadScheduler: Scheduler get() = schedulerService.mainThreadScheduler
        }
    }

}
