package net.corda.client.rpc.internal

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.client.rpc.RPCException
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.pendingFlowsCount
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.internal.config.SSLConfiguration
import org.apache.activemq.artemis.api.core.ActiveMQConnectionTimedOutException
import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException
import org.apache.activemq.artemis.api.core.ActiveMQSecurityException
import org.apache.activemq.artemis.api.core.ActiveMQUnBlockedException
import rx.Observable
import rx.exceptions.OnErrorNotImplementedException
import rx.subjects.PublishSubject
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Utility which exposes the internal Corda RPC constructor to other internal Corda components */
fun createCordaRPCClientWithSsl(
        hostAndPort: NetworkHostAndPort,
        configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default(),
        sslConfiguration: SSLConfiguration? = null
) = CordaRPCClient.createWithSsl(hostAndPort, configuration, sslConfiguration)

fun createCordaRPCClientWithSslAndClassLoader(
        hostAndPort: NetworkHostAndPort,
        configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default(),
        sslConfiguration: SSLConfiguration? = null,
        classLoader: ClassLoader? = null
) = CordaRPCClient.createWithSslAndClassLoader(hostAndPort, configuration, sslConfiguration, classLoader)

fun CordaRPCOps.drainAndShutdown(): Observable<Unit> {

    setFlowsDrainingModeEnabled(true)
    return pendingFlowsCount().updates
            .doOnError { error ->
                throw error
            }
            .doOnCompleted { shutdown() }.map {  }
}

fun CordaRPCClient.shutdownEvent(username: String, password: String, period: Duration = Duration.ofSeconds(1)): Observable<Unit> {

    val nodeIsShut: PublishSubject<Unit> = PublishSubject.create()
    val scheduler = Executors.newSingleThreadScheduledExecutor()

    var task: ScheduledFuture<*>? = null
    return nodeIsShut
            .doOnSubscribe {
                task = scheduler.scheduleAtFixedRate({
                    try {
                        start(username, password).use {
                            // just close the connection
                        }
                    } catch (e: ActiveMQNotConnectedException) {
                        // not cool here, for the connection might be interrupted without the node actually getting shut down - OK for tests
                        println(e)
                        nodeIsShut.onCompleted()
                    } catch (e: ActiveMQConnectionTimedOutException) {
                        // rare, node shut down and we timed out trying to connect to something that doesn't exist
                        println(e)
                        nodeIsShut.onCompleted()
                    } catch (ignored: ActiveMQSecurityException) {
                        // nothing here - this happens if trying to connect before the node is started
                        println(ignored)
                    } catch (e: ActiveMQUnBlockedException) {
                        println(e)
                        nodeIsShut.onCompleted()
                    } catch (e: Throwable) {
                        println(e)
                        nodeIsShut.onError(e)
                    }
                }, 0, period.toMillis(), TimeUnit.MILLISECONDS)
            }
            .doAfterTerminate {
                task?.cancel(true)
                scheduler.shutdown()
            }
}