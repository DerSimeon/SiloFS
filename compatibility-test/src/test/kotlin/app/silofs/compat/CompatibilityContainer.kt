package app.silofs.compat

import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile

internal class CompatibilityContainer(
    image: ImageFromDockerfile,
) : GenericContainer<CompatibilityContainer>(image)
