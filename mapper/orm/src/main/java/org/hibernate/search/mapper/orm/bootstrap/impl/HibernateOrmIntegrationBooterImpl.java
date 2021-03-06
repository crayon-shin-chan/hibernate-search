/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.mapper.orm.bootstrap.impl;

import java.lang.invoke.MethodHandles;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

import org.hibernate.annotations.common.reflection.ReflectionManager;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.resource.beans.container.spi.BeanContainer;
import org.hibernate.resource.beans.container.spi.ExtendedBeanManager;
import org.hibernate.resource.beans.spi.ManagedBeanRegistry;
import org.hibernate.search.engine.cfg.spi.ConfigurationProperty;
import org.hibernate.search.engine.cfg.spi.ConfigurationPropertySource;
import org.hibernate.search.engine.cfg.spi.OptionalConfigurationProperty;
import org.hibernate.search.engine.common.spi.SearchIntegration;
import org.hibernate.search.engine.common.spi.SearchIntegrationBuilder;
import org.hibernate.search.engine.common.spi.SearchIntegrationFinalizer;
import org.hibernate.search.engine.common.spi.SearchIntegrationPartialBuildState;
import org.hibernate.search.engine.environment.bean.spi.BeanProvider;
import org.hibernate.search.engine.environment.bean.spi.ReflectionBeanProvider;
import org.hibernate.search.mapper.orm.bootstrap.spi.HibernateOrmIntegrationBooter;
import org.hibernate.search.engine.cfg.spi.ConfigurationPropertyChecker;
import org.hibernate.search.mapper.orm.cfg.spi.HibernateOrmMapperSpiSettings;
import org.hibernate.search.mapper.orm.logging.impl.Log;
import org.hibernate.search.mapper.orm.mapping.impl.HibernateOrmMapping;
import org.hibernate.search.mapper.orm.mapping.impl.HibernateSearchContextProviderService;
import org.hibernate.search.mapper.orm.mapping.impl.HibernateOrmMappingInitiator;
import org.hibernate.search.mapper.orm.mapping.impl.HibernateOrmMappingKey;
import org.hibernate.search.mapper.orm.spi.EnvironmentSynchronizer;
import org.hibernate.search.util.common.AssertionFailure;
import org.hibernate.search.util.common.impl.Futures;
import org.hibernate.search.util.common.impl.SuppressingCloser;
import org.hibernate.search.util.common.logging.impl.LoggerFactory;
import org.hibernate.service.Service;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.service.spi.ServiceBinding;
import org.hibernate.service.spi.ServiceRegistryImplementor;

public class HibernateOrmIntegrationBooterImpl implements HibernateOrmIntegrationBooter {

	private static final Log log = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	@SuppressWarnings("unchecked")
	static ConfigurationPropertySource getPropertySource(ServiceRegistry serviceRegistry,
			ConfigurationPropertyChecker propertyChecker) {
		return propertyChecker.wrap(
				ConfigurationPropertySource.fromMap(
						serviceRegistry.getService( ConfigurationService.class )
								.getSettings()
				)
		);
	}

	private static final OptionalConfigurationProperty<HibernateOrmIntegrationPartialBuildState> INTEGRATION_PARTIAL_BUILD_STATE =
			ConfigurationProperty.forKey( HibernateOrmMapperSpiSettings.INTEGRATION_PARTIAL_BUILD_STATE )
					.as( HibernateOrmIntegrationPartialBuildState.class, HibernateOrmIntegrationPartialBuildState::parse )
					.build();
	private final Metadata metadata;
	private final ServiceRegistryImplementor serviceRegistry;
	private final ReflectionManager reflectionManager;
	private final ConfigurationService ormConfigurationService;
	private final ConfigurationPropertySource rootPropertySource;
	private final ConfigurationPropertyChecker propertyChecker;

	private final Optional<EnvironmentSynchronizer> environmentSynchronizer;

	public HibernateOrmIntegrationBooterImpl(Metadata metadata, BootstrapContext bootstrapContext) {
		this(
				metadata, bootstrapContext,
				ConfigurationPropertyChecker.create()
		);
	}

	private HibernateOrmIntegrationBooterImpl(Metadata metadata, BootstrapContext bootstrapContext,
			ConfigurationPropertyChecker propertyChecker) {
		this(
				metadata, bootstrapContext,
				getPropertySource( bootstrapContext.getServiceRegistry(), propertyChecker ),
				propertyChecker
		);
	}

	@SuppressWarnings("deprecation") // There is no alternative to getReflectionManager() at the moment.
	HibernateOrmIntegrationBooterImpl(Metadata metadata, BootstrapContext bootstrapContext,
			ConfigurationPropertySource checkerWrappedPropertySource, ConfigurationPropertyChecker propertyChecker) {
		this.metadata = metadata;
		this.serviceRegistry = (ServiceRegistryImplementor) bootstrapContext.getServiceRegistry();
		this.reflectionManager = bootstrapContext.getReflectionManager();
		this.rootPropertySource = checkerWrappedPropertySource;
		this.propertyChecker = propertyChecker;
		this.ormConfigurationService = serviceRegistry.getService( ConfigurationService.class );

		Optional<EnvironmentSynchronizer> providedEnvironmentSynchronizer = getOrmServiceOrEmpty( EnvironmentSynchronizer.class );
		if ( providedEnvironmentSynchronizer.isPresent() ) {
			// Allow integrators to override the environment synchronizer with an ORM Service
			this.environmentSynchronizer = providedEnvironmentSynchronizer;
		}
		else {
			Object unknownBeanManager = ormConfigurationService.getSettings().get( AvailableSettings.CDI_BEAN_MANAGER );
			if ( unknownBeanManager instanceof ExtendedBeanManager ) {
				ExtendedBeanManager extendedBeanManager = (ExtendedBeanManager) unknownBeanManager;
				ExtendedBeanManagerSynchronizer synchronizer = new ExtendedBeanManagerSynchronizer();
				extendedBeanManager.registerLifecycleListener( synchronizer );
				this.environmentSynchronizer = Optional.of( synchronizer );
			}
			else {
				this.environmentSynchronizer = Optional.empty();
			}
		}
	}

	@Override
	public void preBoot(BiConsumer<String, Object> propertyCollector) {
		if ( environmentSynchronizer.isPresent() ) {
			throw new AssertionFailure(
					"Cannot pre-boot when an environment synchronizer is used to delay Hibernate Search's bootstrap: "
					+ " we cannot both delay bootstrap and perform it earlier."
			);
		}

		HibernateOrmIntegrationPartialBuildState partialBuildState = doBootFirstPhase();
		propertyCollector.accept( HibernateOrmMapperSpiSettings.INTEGRATION_PARTIAL_BUILD_STATE, partialBuildState );
	}

	CompletableFuture<HibernateSearchContextProviderService> orchestrateBootAndShutdown(
			CompletionStage<SessionFactoryImplementor> sessionFactoryReadyStage,
			CompletionStage<?> sessionFactoryDestroyingStage) {
		CompletableFuture<HibernateSearchContextProviderService> contextFuture = new CompletableFuture<>();

		CompletableFuture<Void> environmentSynchronizerReadyStage = new CompletableFuture<>();
		CompletableFuture<Void> environmentSynchronizerStartedDestroyingStage = new CompletableFuture<>();

		if ( environmentSynchronizer.isPresent() ) {
			environmentSynchronizer.get().whenEnvironmentDestroying( () -> {
				environmentSynchronizerStartedDestroyingStage.complete( null );
				// If the above triggered shutdown and it failed, the exception will be logged.
			} );
			environmentSynchronizer.get().whenEnvironmentReady( () -> {
				environmentSynchronizerReadyStage.complete( null );
				// If the above triggered bootstrap and it failed, propagate the exception.
				if ( contextFuture.isCompletedExceptionally() ) {
					Futures.unwrappedExceptionJoin( contextFuture );
				}
			} );
		}
		else {
			/*
			 * Assume the environment synchronizer is always ready.
			 * Do not care about the "started destroying" event,
			 * if it is not triggered then the session lifecycle will prevail.
			 */
			environmentSynchronizerReadyStage.complete( null );
		}

		/*
		 * Boot is required as soon as both the environment synchronizer *and* the session factory are ready.
		 */
		CompletableFuture<SessionFactoryImplementor> bootRequiredStage =
				environmentSynchronizerReadyStage.thenCombine(
						sessionFactoryReadyStage, (ignored, sessionFactory) -> sessionFactory
				);

		/*
		 * A shutdown is required as soon as the session factory starts being destroyed,
		 * *or* the environment synchronizer signals destroying is starting.
		 */
		CompletionStage<?> shutdownRequiredStage = CompletableFuture.anyOf(
				environmentSynchronizerStartedDestroyingStage, sessionFactoryDestroyingStage.toCompletableFuture()
		);

		/*
		 * As soon as boot is required, we need to, well... boot.
		 */
		bootRequiredStage.thenApply( this::bootNow )
				// Notify whoever wants to hear about the result of the boot.
				.whenComplete( Futures.copyHandler( contextFuture ) );

		/*
		 * As soon as a shutdown is required,
		 * we need to cancel the boot if it's still possible,
		 * or shut down Hibernate Search if it already started.
		 */
		shutdownRequiredStage.thenRun( () -> bootRequiredStage.cancel( false ) );
		// Ignore bootstrap failures
		contextFuture.exceptionally( throwable -> null )
				.thenAcceptBoth( shutdownRequiredStage, (context, ignored) -> {
					if ( context != null ) {
						context.close();
					}
				} )
				// If the above triggered shutdown and it failed, log the exception.
				// We don't propagate it because that may cause the environment
				// to skip further cleanup of other resources.
				.whenComplete( Futures.handler( (ignored, throwable) -> {
					if ( throwable != null ) {
						log.shutdownFailed( throwable.getMessage(), throwable );
					}
				} ) );

		return contextFuture;
	}

	private HibernateSearchContextProviderService bootNow(SessionFactoryImplementor sessionFactoryImplementor) {
		Optional<HibernateOrmIntegrationPartialBuildState> partialBuildStateOptional =
				INTEGRATION_PARTIAL_BUILD_STATE.get( rootPropertySource );

		HibernateOrmIntegrationPartialBuildState partialBuildState;
		if ( partialBuildStateOptional.isPresent() ) {
			// The first phase of booting was already performed externally; just re-use the result
			partialBuildState = partialBuildStateOptional.get();
		}
		else {
			partialBuildState = doBootFirstPhase();
		}

		try {
			return doBootSecondPhase( partialBuildState, sessionFactoryImplementor );
		}
		catch (RuntimeException e) {
			new SuppressingCloser( e )
					.push( HibernateOrmIntegrationPartialBuildState::closeOnFailure, partialBuildState );
			throw e;
		}
	}

	/*
	 * Do not change this method's signature and do not stop using it:
	 * it's overridden by Quarkus to make it clear to SubstrateVM
	 * that the first phase of boot is never executed in the native binary.
	 */
	private HibernateOrmIntegrationPartialBuildState doBootFirstPhase() {
		ReflectionBeanProvider reflectionBeanProvider = null;
		BeanProvider beanProvider = null;
		SearchIntegrationPartialBuildState searchIntegrationPartialBuildState = null;
		try {
			SearchIntegrationBuilder builder = SearchIntegration.builder( rootPropertySource, propertyChecker );

			HibernateOrmMappingKey mappingKey = new HibernateOrmMappingKey();
			HibernateOrmMappingInitiator mappingInitiator = HibernateOrmMappingInitiator.create(
					metadata, reflectionManager, ormConfigurationService,
					builder.maskedPropertySource()
			);
			builder.addMappingInitiator( mappingKey, mappingInitiator );

			ClassLoaderService hibernateOrmClassLoaderService = getOrmServiceOrFail( ClassLoaderService.class );
			Optional<ManagedBeanRegistry> managedBeanRegistryService = getOrmServiceOrEmpty( ManagedBeanRegistry.class );
			HibernateOrmClassLoaderServiceClassAndResourceAndServiceResolver classAndResourceAndServiceResolver =
					new HibernateOrmClassLoaderServiceClassAndResourceAndServiceResolver( hibernateOrmClassLoaderService );
			builder.classResolver( classAndResourceAndServiceResolver );
			builder.resourceResolver( classAndResourceAndServiceResolver );
			builder.serviceResolver( classAndResourceAndServiceResolver );

			reflectionBeanProvider = ReflectionBeanProvider.create( classAndResourceAndServiceResolver );
			if ( managedBeanRegistryService.isPresent() ) {
				BeanContainer beanContainer = managedBeanRegistryService.get().getBeanContainer();
				if ( beanContainer != null ) {
					// Only use the primary registry, so that we can implement our own fallback when beans are not found
					beanProvider = new HibernateOrmBeanContainerBeanProvider( beanContainer, reflectionBeanProvider );
				}
				// else: The given ManagedBeanRegistry only implements fallback: let's ignore it
			}
			if ( beanProvider == null ) {
				beanProvider = reflectionBeanProvider;
			}
			builder.beanProvider( beanProvider );

			// TODO HSEARCH-3057 namingService (JMX)? Or maybe in second phase?

			searchIntegrationPartialBuildState = builder.prepareBuild();

			return new HibernateOrmIntegrationPartialBuildState(
					searchIntegrationPartialBuildState,
					mappingKey
			);
		}
		catch (RuntimeException e) {
			new SuppressingCloser( e )
					.push( SearchIntegrationPartialBuildState::closeOnFailure, searchIntegrationPartialBuildState )
					.push( BeanProvider::close, reflectionBeanProvider )
					.push( BeanProvider::close, beanProvider );
			throw e;
		}
	}

	private HibernateSearchContextProviderService doBootSecondPhase(
			HibernateOrmIntegrationPartialBuildState partialBuildState,
			SessionFactoryImplementor sessionFactoryImplementor) {
		SearchIntegrationFinalizer finalizer =
				partialBuildState.integrationBuildState.finalizer( rootPropertySource, propertyChecker );

		HibernateOrmMapping mapping = finalizer.finalizeMapping(
				partialBuildState.mappingKey,
				(context, partialMapping) -> partialMapping.bindToSessionFactory( context, sessionFactoryImplementor )
		);
		SearchIntegration integration = finalizer.finalizeIntegration();

		/*
		 * Make the booted integration available to the user (through Search.getFullTextEntityManager(em))
		 * and to the index event listener.
		 */
		HibernateSearchContextProviderService contextService =
				sessionFactoryImplementor.getServiceRegistry().getService( HibernateSearchContextProviderService.class );
		contextService.initialize( integration, mapping );

		// TODO HSEARCH-3057 JMX
//		this.jmx = new JMXHook( propertySource );
//		this.jmx.registerIfEnabled( extendedIntegrator, factory );

		return contextService;
	}

	private <T extends Service> T getOrmServiceOrFail(Class<T> serviceClass) {
		T service = serviceRegistry.getService( serviceClass );
		if ( service == null ) {
			throw new AssertionFailure(
					"A required service was missing; there is probably a bug in Hibernate ORM or Hibernate Search."
					+ " Missing service: " + serviceClass
			);
		}
		return service;
	}

	private <T extends Service> Optional<T> getOrmServiceOrEmpty(Class<T> serviceClass) {
		/*
		 * First check the service binding, because if it does not exist,
		 * a call to serviceRegistry.getService would throw an exception.
 		 */
		ServiceBinding<T> binding = serviceRegistry.locateServiceBinding( serviceClass );
		if ( binding == null ) {
			// The service binding does not exist, so the service does not exist
			return Optional.empty();
		}
		else {
			// The service binding exists, so the service may exist
			// Retrieve it from the service registry, not from the binding, to be sure it's initialized
			// Note the service may be null, even if the binding is defined
			return Optional.ofNullable( serviceRegistry.getService( serviceClass ) );
		}
	}

	private static final class HibernateOrmIntegrationPartialBuildState {

		static HibernateOrmIntegrationPartialBuildState parse(String stringToParse) {
			throw new AssertionFailure(
					"The partial build state cannot be parsed from a String;"
							+ " it must be null or an instance of " + HibernateOrmIntegrationPartialBuildState.class
			);
		}

		private final SearchIntegrationPartialBuildState integrationBuildState;
		private final HibernateOrmMappingKey mappingKey;

		HibernateOrmIntegrationPartialBuildState(
				SearchIntegrationPartialBuildState integrationBuildState,
				HibernateOrmMappingKey mappingKey) {
			this.integrationBuildState = integrationBuildState;
			this.mappingKey = mappingKey;
		}

		void closeOnFailure() {
			this.integrationBuildState.closeOnFailure();
		}
	}
}
