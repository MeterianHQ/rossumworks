package com.meterian.factory.configuration;

/** The name of the repository on GitHub, for example MeterianHQ/heidi-fix. */
public record RepositoryName(String owner, String name) {

    public static RepositoryName parse(String fullName) throws ConfigurationException {
        String[] parts = fullName.split("/", -1);
        boolean hasOwnerAndName = parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank();
        if (!hasOwnerAndName) {
            throw new ConfigurationException("Configuration key repository must have the form owner/name, "
                    + "for example MeterianHQ/heidi-fix.");
        }
        return new RepositoryName(parts[0], parts[1]);
    }

    public String fullName() {
        return owner + "/" + name;
    }

    public String sshUrl() {
        return "git@github.com:" + fullName() + ".git";
    }
}
