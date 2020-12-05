package io.javaoperatorsdk.operator.sample;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.quarkus.runtime.Startup;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Singleton;

@ApplicationScoped
class Config {

    @Singleton
    public CustomServiceController customServiceController(KubernetesClient client) {
        return new CustomServiceController(client);
    }

    @Singleton
    @Startup
    public Operator operator(KubernetesClient client, Instance<ResourceController<?>> controllers) {
        Operator operator = new Operator(client);
        controllers.forEach(operator::registerControllerForAllNamespaces);
        return operator;
    }
}