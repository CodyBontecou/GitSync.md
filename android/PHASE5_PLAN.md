# Phase 5: Android UI with Jetpack Compose

## Overview
Build native Android UI using Jetpack Compose, connected to the pure Kotlin JVM core logic from Phases 1-4.

## Architecture

```
app/                          # Android app module
├── src/main/kotlin/
│   └── com/bontecou/syncmd/
│       ├── MainActivity.kt
│       ├── SyncMdApp.kt      # Compose app wrapper
│       ├── Navigation.kt      # Navigation graph
│       ├── ui/
│       │   ├── screens/       # Main feature screens
│       │   │   ├── StatusScreen.kt
│       │   │   ├── PullScreen.kt
│       │   │   ├── CommitScreen.kt
│       │   │   ├── BranchScreen.kt
│       │   │   ├── MergeScreen.kt
│       │   │   ├── ConflictScreen.kt
│       │   │   ├── HistoryScreen.kt
│       │   │   └── StashScreen.kt
│       │   ├── viewmodels/    # MVVM ViewModels
│       │   │   ├── SyncViewModel.kt
│       │   │   ├── PullViewModel.kt
│       │   │   ├── CommitViewModel.kt
│       │   │   ├── BranchViewModel.kt
│       │   │   ├── ConflictViewModel.kt
│       │   │   ├── HistoryViewModel.kt
│       │   │   └── StashViewModel.kt
│       │   ├── components/    # Reusable Compose components
│       │   │   ├── FileList.kt
│       │   │   ├── DiffViewer.kt
│       │   │   ├── ConflictResolver.kt
│       │   │   ├── CommitList.kt
│       │   │   ├── BranchList.kt
│       │   │   ├── StatusCard.kt
│       │   │   └── ...
│       │   └── theme/
│       │       ├── Color.kt
│       │       ├── Type.kt
│       │       └── Theme.kt
│       ├── data/
│       │   └── repository/    # Android-specific repos (SAF, Cache, etc.)
│       └── utils/
└── build.gradle.kts

core/                         # Pure Kotlin JVM (from Phases 1-4)
├── src/main/...             # Models, interfaces, services
└── src/test/...             # Protocol tests
```

## Implementation Plan

### Phase 5.0: Project Setup
- [ ] Create `app` module with Android dependencies
- [ ] Add AGP (Android Gradle Plugin) with API 34
- [ ] Set up Jetpack Compose, Hilt, Navigation
- [ ] Create MainActivity and Compose entry point
- [ ] Verify compilation and basic app launch

### Phase 5.1: Navigation & State (A1 core)
- [ ] Create Navigation.kt with NavGraph
- [ ] Build SyncViewModel with repository injection
- [ ] Set up Hilt DI for ViewModels
- [ ] Implement state flows for UI reactivity
- [ ] Create basic screen structure

### Phase 5.2: Status & Pull UI (A1)
- [ ] StatusScreen - display repo status with file list
- [ ] StatusCard component for health indicator
- [ ] FileList component for modified/staged/untracked files
- [ ] PullScreen - plan pull, show merge type, execute
- [ ] Progress indicator during pull execution

### Phase 5.3: Diff & Commit UI (A2)
- [ ] CommitScreen - stage/unstage files, write message
- [ ] DiffViewer component - show unified diffs
- [ ] Hunk-level diff display
- [ ] File selection for staging
- [ ] Commit message editor with character count

### Phase 5.4: Branch Management UI (A3)
- [ ] BranchScreen - list all branches
- [ ] BranchList component with create/delete/switch
- [ ] Branch type indicators (local, remote, tracking)
- [ ] CreateBranchDialog
- [ ] MergeScreen - select source, choose strategy, execute

### Phase 5.5: Conflict Resolution UI (A4)
- [ ] ConflictScreen - show conflicting files
- [ ] ConflictResolver component with OURS/THEIRS/MANUAL options
- [ ] Inline conflict viewer showing markers
- [ ] Bulk resolution UI (resolve all with strategy)
- [ ] Merge completion flow

### Phase 5.6: History & Recovery UI (A5)
- [ ] HistoryScreen - commit log with search/filter
- [ ] CommitList component with expandable details
- [ ] RevertDialog with strategy selection
- [ ] StashScreen - list, apply, pop, drop stashes
- [ ] TagScreen - create, delete, list tags

### Phase 5.7: Polish & Integration
- [ ] Error handling and user feedback (Snackbar, Dialog)
- [ ] Loading states and progress indicators
- [ ] Bottom navigation between main features
- [ ] Settings screen for repo selection
- [ ] Theme (light/dark mode support)
- [ ] Accessibility (content descriptions, etc.)

## Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| UI Framework | Jetpack Compose | Latest stable |
| Navigation | Compose Navigation | Latest stable |
| DI | Hilt | Latest stable |
| State | StateFlow / ViewModel | Latest stable |
| Coroutines | Kotlin Coroutines | 1.8.1+ |
| Core | Pure Kotlin JVM (from Phase 4a) | - |

## Design Principles

1. **Reactive**: State flows from ViewModels to UI via Compose
2. **Modular**: Reusable Compose components
3. **Safe**: Use Result<T> pattern from core layer
4. **Testable**: ViewModels use FakeRepository in tests
5. **Accessible**: Proper content descriptions and navigation

## Integration Points

- **ViewModels** inject Service instances from core
- **Services** use Local*Repository implementations (real git)
- **Tests** can swap FakeRepository without changing UI code
- **Error handling** propagated from core Result<T> to UI

## Success Criteria

1. All 8 screens implemented and navigable
2. UI correctly displays all features from core logic
3. Real git operations work through all screens
4. Error states properly displayed
5. No crashes or ANRs
6. Compose preview tests for components
7. Accessible to assistive tech

## Timeline Estimate
- Phase 5.0: 30 mins (setup)
- Phase 5.1: 1 hour (nav + state)
- Phase 5.2: 1.5 hours (status + pull)
- Phase 5.3: 1.5 hours (diff + commit)
- Phase 5.4: 1 hour (branch)
- Phase 5.5: 1 hour (conflict)
- Phase 5.6: 1 hour (history + stash)
- Phase 5.7: 1 hour (polish)
- **Total**: ~8 hours

## Risk Mitigation

- **Risk**: Compose Layout issues → Use standard layouts (Column, LazyColumn, etc.)
- **Risk**: State management bugs → Compose preview tests
- **Risk**: Git command failures → Use Result<T> error handling
- **Risk**: Large repos slow → Pagination in lists, lazy loading
- **Risk**: Memory leaks → Proper ViewModel lifecycle, no Context in compose lambdas

## Notes

- Core logic (Phases 1-4a) is 100% tested and production-ready
- UI layer uses FakeRepository for testing before integration
- No Android framework code in core (stays pure Kotlin JVM)
- All git operations go through service layer
