package software.aws.toolkits.jetbrains.core

import assertk.assert
import assertk.assertions.hasMessageContaining
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameAs
import assertk.assertions.isSameAs
import assertk.assertions.isTrue
import com.intellij.openapi.components.ServiceManager
import com.intellij.testFramework.PlatformTestCase
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.runInEdtAndWait
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import software.amazon.awssdk.core.SdkClient
import software.amazon.awssdk.core.auth.Signer
import software.amazon.awssdk.core.client.builder.ClientBuilder
import software.amazon.awssdk.core.client.builder.DefaultClientBuilder
import software.amazon.awssdk.core.client.builder.SyncClientBuilder
import software.amazon.awssdk.core.config.defaults.ClientConfigurationDefaults
import software.amazon.awssdk.core.config.defaults.ServiceBuilderConfigurationDefaults
import software.amazon.awssdk.core.runtime.auth.SignerProvider
import software.amazon.awssdk.core.runtime.auth.SignerProviderContext
import software.amazon.awssdk.http.SdkHttpClient
import software.aws.toolkits.jetbrains.core.region.AwsRegion
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

class AwsClientManagerTest {

    @Rule
    @JvmField
    val projectRule = ProjectRule()

    @Rule
    @JvmField
    val temporaryDirectory = TemporaryFolder()

    @Test
    fun canGetAnInstanceOfAClient() {
        val sut = AwsClientManager.getInstance(projectRule.project)
        val client = sut.getClient<DummyServiceClient>()
        assert(client.serviceName()).isEqualTo("dummyClient")
    }

    @Test
    fun clientsAreCached() {
        val sut = AwsClientManager.getInstance(projectRule.project)
        val fooClient = sut.getClient<DummyServiceClient>()
        val barClient = sut.getClient<DummyServiceClient>()

        assert(fooClient).isSameAs(barClient)
    }

    @Test
    fun clientsAreClosedWhenProjectIsDisposed() {
        val project = PlatformTestCase.createProject(temporaryDirectory.newFolder(), "Fake project")
        val sut = AwsClientManager.getInstance(project)
        val client = sut.getClient<DummyServiceClient>()

        runInEdtAndWait {
            PlatformTestCase.closeAndDisposeProjectAndCheckThatNoOpenProjects(project)
        }

        assert(client.closed).isTrue()
    }

    @Test
    fun httpClientIsSharedAcrossClients() {
        val sut = AwsClientManager.getInstance(projectRule.project)
        val dummy = sut.getClient<DummyServiceClient>()
        val secondDummy = sut.getClient<SecondDummyServiceClient>()

        assert(dummy.httpClient.delegate).isSameAs(secondDummy.httpClient.delegate)
    }

    @Test
    fun clientWithoutBuilderFailsDescriptively() {
        val sut = AwsClientManager.getInstance(projectRule.project)

        assert { sut.getClient<InvalidServiceClient>() }.thrownError {
            isInstanceOf(IllegalArgumentException::class)
            hasMessageContaining("builder()")
        }
    }

    @Test
    fun newClientCreatedWhenRegionChanges() {
        val sut = AwsClientManager.getInstance(projectRule.project)
        val first = sut.getClient<DummyServiceClient>()

        val testSettings = ServiceManager.getService(projectRule.project, AwsSettingsProvider::class.java)

        testSettings.currentRegion = AwsRegion("us-east-1", "US-east-1")

        val afterRegionUpdate = sut.getClient<DummyServiceClient>()

        assert(afterRegionUpdate).isNotSameAs(first)
    }

    class DummyServiceClient(val httpClient: SdkHttpClient) : TestClient() {

        companion object {
            @Suppress("unused")
            @JvmStatic
            fun builder(): DummyServiceClientBuilder = DummyServiceClientBuilder()
        }
    }

    class DummyServiceClientBuilder : TestClientBuilder<DummyServiceClientBuilder, DummyServiceClient>(),
            SyncClientBuilder<DummyServiceClientBuilder, DummyServiceClient> {
        override fun buildClient(): DummyServiceClient = DummyServiceClient(syncClientConfiguration().httpClient())
    }

    class SecondDummyServiceClient(val httpClient: SdkHttpClient) : TestClient() {

        companion object {
            @Suppress("unused")
            @JvmStatic
            fun builder(): SecondDummyServiceClientBuilder = SecondDummyServiceClientBuilder()
        }
    }

    class SecondDummyServiceClientBuilder : TestClientBuilder<SecondDummyServiceClientBuilder, SecondDummyServiceClient>(),
            SyncClientBuilder<SecondDummyServiceClientBuilder, SecondDummyServiceClient> {
        override fun buildClient(): SecondDummyServiceClient = SecondDummyServiceClient(syncClientConfiguration().httpClient())
    }

    class InvalidServiceClient : SdkClient {
        override fun serviceName(): String = "invalidClient"
    }

    abstract class TestClient : SdkClient, AutoCloseable {
        var closed = false

        override fun serviceName(): String = "dummyClient"

        override fun close() {
            closed = true
        }
    }

    abstract class TestClientBuilder<B : ClientBuilder<B, C>, C> : DefaultClientBuilder<B, C>() {
        override fun serviceEndpointPrefix(): String = "dummyClient"

        override fun serviceDefaults(): ClientConfigurationDefaults {
            return ServiceBuilderConfigurationDefaults.builder()
                    .defaultSignerProvider(
                            {
                                object : SignerProvider() {
                                    override fun getSigner(context: SignerProviderContext?) = Signer { ctx, _ -> ctx.httpRequest() }
                                }
                            })
                    .build()
        }
    }

    private val SdkHttpClient.delegate: SdkHttpClient
        get() {
            val delegateProperty = this::class.declaredMemberProperties.find { it.name == "delegate" }
                    ?: throw IllegalArgumentException("Expected instance of software.amazon.awssdk.core.client.builder.DefaultClientBuilder.NonManagedSdkHttpClient")
            delegateProperty.isAccessible = true
            return delegateProperty.call(this) as SdkHttpClient
        }
}