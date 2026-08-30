#ifndef CLIBGIT2_SYS_H
#define CLIBGIT2_SYS_H

#include <git2.h>

int clibgit2_repository_set_index(git_repository *repository, git_index *index);

#endif /* CLIBGIT2_SYS_H */
