#ifndef CLIBGIT2_SYS_H
#define CLIBGIT2_SYS_H

#include <git2.h>

int clibgit2_repository_set_index(git_repository *repository, git_index *index);
int clibgit2_repository_use_readonly_unicode_config(git_repository *repository);

#endif /* CLIBGIT2_SYS_H */
