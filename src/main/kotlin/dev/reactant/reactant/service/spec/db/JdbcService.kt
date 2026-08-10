package dev.reactant.reactant.service.spec.db

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.sql.PreparedStatement
import java.sql.ResultSet

interface JdbcService {
    interface ServicePreparedStatement {
        fun config(configFunc: PreparedStatement.() -> Unit): ServicePreparedStatement
        fun execute(): Single<Boolean>
        fun <T : Any> executeQuery(onNext: ResultSet.() -> T): Observable<T>
        fun executeQueryToResultSet(): Single<ResultSet>
        fun executeBatch(): Single<IntArray>
        val connection: ServiceConnection
    }

    interface ServiceConnection {
        fun prepareStatement(statement: String): ServicePreparedStatement
        fun commit(): Completable
        fun close(): Completable
        val isClosed: Boolean
    }

    fun getConnection(dbUrl: String, dbUsername: String, dbPassword: String, autoCommit: Boolean = true): Single<out ServiceConnection>
}
