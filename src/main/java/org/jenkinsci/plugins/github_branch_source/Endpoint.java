/*
 * The MIT License
 *
 * Copyright 2015 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.github_branch_source;

import com.fasterxml.jackson.core.JsonParseException;
import com.google.common.net.InternetDomainName;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.github.GitHub;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * @author Stephen Connolly
 */
public class Endpoint extends AbstractDescribableImpl<Endpoint> {
    /**
     * Common prefixes that we should remove when inferring a display name.
     */
    private static final String[] COMMON_PREFIX_HOSTNAMES = {
            "git.",
            "github.",
            "vcs.",
            "scm.",
            "source."
    };

    private final String name;
    private final String apiUri;

    /**
     * Makes best effort to guess a "sensible" display name from the hostname in the apiUri.
     *
     * @param apiUri the apiUri.
     * @return the display name or {@code null}
     * @throws LinkageError if Guava changes their API that we have depended on.
     */
    @CheckForNull
    /*package*/ static String inferDisplayName(@CheckForNull String apiUri) throws LinkageError {
        if (apiUri == null) {
            return apiUri;
        }
        String hostName;
        try {
            URI serverUri = new URI(apiUri);
            hostName = serverUri.getHost();
            if (hostName != null) {
                // let's see if we can make this more "friendly"
                InternetDomainName host = InternetDomainName.from(hostName);
                if (host.hasPublicSuffix()) {
                    String publicName = host.publicSuffix().name();
                    hostName = StringUtils.removeEnd(StringUtils.removeEnd(host.name(), publicName), ".")
                            .toLowerCase(Locale.ENGLISH);
                } else {
                    hostName = StringUtils.removeEnd(host.name(), ".").toLowerCase(Locale.ENGLISH);
                }
                for (String prefix : COMMON_PREFIX_HOSTNAMES) {
                    if (hostName.startsWith(prefix)) {
                        hostName = hostName.substring(prefix.length());
                        break;
                    }
                }
            }
        } catch (URISyntaxException e) {
            // ignore, best effort
            hostName = null;
        }
        return hostName;
    }

    @DataBoundConstructor
    public Endpoint(String apiUri, String name) {
        this.apiUri = GitHubConfiguration.normalizeApiUri(Util.fixEmptyAndTrim(apiUri));
        name = Util.fixEmptyAndTrim(name);
        if (name == null) {
            this.name = inferDisplayName(apiUri);
        } else {
            this.name = name;
        }
    }

    private Object readResolve() throws ObjectStreamException {
        if (!apiUri.equals(GitHubConfiguration.normalizeApiUri(apiUri))) {
            return new Endpoint(apiUri, name);
        }
        return this;
    }

    @NonNull
    public String getApiUri() {
        return apiUri;
    }

    @CheckForNull
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Endpoint{");
        sb.append("apiUrl='").append(apiUri).append('\'');
        sb.append(", name='").append(name).append('\'');
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Endpoint)) {
            return false;
        }

        Endpoint endpoint = (Endpoint) o;

        if (apiUri != null ? !apiUri.equals(endpoint.apiUri) : endpoint.apiUri != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return apiUri != null ? apiUri.hashCode() : 0;
    }

    @Extension
    public static class DesciptorImpl extends Descriptor<Endpoint> {

        private static final Logger LOGGER = Logger.getLogger(DesciptorImpl.class.getName());

        @Override
        public String getDisplayName() {
            return "";
        }

        @Restricted(NoExternalUse.class)
        public FormValidation doCheckApiUri(@QueryParameter String apiUri) {
            if (Util.fixEmptyAndTrim(apiUri) == null) {
                return FormValidation.warning("You must specify the API URL");
            }
            try {
                URL api = new URL(apiUri);
                GitHub github = GitHub.connectToEnterpriseAnonymously(api.toString());
                github.checkApiUrlValidity();
                LOGGER.log(Level.FINE, "Trying to configure a GitHub Enterprise server");
                // For example: https://api.github.com/ or https://github.mycompany.com/api/v3/ (with private mode disabled).
                return FormValidation.ok("GitHub Enterprise server verified");
            } catch (MalformedURLException mue) {
                // For example: https:/api.github.com
                LOGGER.log(Level.WARNING, "Trying to configure a GitHub Enterprise server: " + apiUri, mue.getCause());
                return FormValidation.error("The endpoint does not look like a GitHub Enterprise (malformed URL)");
            } catch (JsonParseException jpe) {
                LOGGER.log(Level.WARNING, "Trying to configure a GitHub Enterprise server: " + apiUri, jpe.getCause());
                return FormValidation.error("The endpoint does not look like a GitHub Enterprise (invalid JSON response)");
            } catch (FileNotFoundException fnt) {
                // For example: https://github.mycompany.com/server/api/v3/ gets a FileNotFoundException
                LOGGER.log(Level.WARNING, "Getting HTTP Error 404 for " + apiUri);
                return FormValidation.error("The endpoint does not look like a GitHub Enterprise (page not found");
            } catch (IOException e) {
                // For example: https://github.mycompany.com/api/v3/ or https://github.mycompany.com/api/v3/mypath
                if (e.getMessage().contains("private mode enabled")) {
                    LOGGER.log(Level.FINE, e.getMessage());
                    return FormValidation.warning("Private mode enabled, validation disabled");
                }
                LOGGER.log(Level.WARNING, e.getMessage());
                return FormValidation.error("The endpoint does not look like a GitHub Enterprise (verify network and/or try again later)");
            }
        }

        @Restricted(NoExternalUse.class)
        public FormValidation doCheckName(@QueryParameter String name) {
            if (Util.fixEmptyAndTrim(name) == null) {
                return FormValidation.warning("A name is recommended to help differentiate similar endpoints");
            }
            return FormValidation.ok();
        }
    }
}
