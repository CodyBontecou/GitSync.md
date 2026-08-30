#include "Clibgit2Sys.h"

// The prebuilt module's umbrella header omits git2/sys/repository.h, but the
// linked libgit2 archive exports this documented system-level symbol.
extern int git_repository_set_index(git_repository *repository, git_index *index);

int clibgit2_repository_set_index(git_repository *repository, git_index *index) {
    return git_repository_set_index(repository, index);
}
