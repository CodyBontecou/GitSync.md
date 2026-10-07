#include "Clibgit2Sys.h"
#include <git2/sys/config.h>
#include <git2/sys/repository.h>
#include <string.h>

int clibgit2_repository_set_index(git_repository *repository, git_index *index) {
    return git_repository_set_index(repository, index);
}

// Status reads need Unicode normalization without migrating .git/config. A
// foreground refresh may run while another workflow holds a reviewed snapshot.
// Preserve the repository's effective settings and add a read-only override
// that lives only as long as this repository handle.
int clibgit2_repository_use_readonly_unicode_config(git_repository *repository) {
    git_config *config = NULL;
    git_config *effective_snapshot = NULL;
    git_config_backend *backend = NULL;
    const char *override = "[core]\nprecomposeunicode = true\n";
    int result = git_repository_config(&config, repository);
    if (result < 0) goto cleanup;

    result = git_config_backend_from_string(&backend, override, strlen(override), NULL);
    if (result < 0) goto cleanup;

    result = git_config_add_backend(config, backend, GIT_CONFIG_LEVEL_APP, repository, 0);
    if (result < 0) goto cleanup;
    backend = NULL; // The config owns the backend after a successful add.

    // Snapshot the memory override before any getter is called. libgit2 1.9.2's
    // memory getter does not retain its shared entry list, while the immutable
    // snapshot backend provides correctly owned entries to status reads.
    result = git_config_snapshot(&effective_snapshot, config);
    if (result < 0) goto cleanup;
    result = git_repository_set_config(repository, effective_snapshot);

cleanup:
    if (backend) backend->free(backend);
    git_config_free(effective_snapshot);
    git_config_free(config);
    return result;
}
