# Restoring gitaly data corruption on a project after an unclean shutdown

## Why does this happen?

For all we know commits and git objects themselves are not lost, but in some cases refs getting updated might result in them getting stored as an empty file.
It is likely, that gitaly wants to update the ref, but did not sync the content to disk before having the host fail, so the file containing the ref was corrupted in the process.

Objects are fine (with errors being detectable that is) as they are hashed, and `fsync()`ed by git (<https://gitlab.com/gitlab-org/gitaly/-/issues/1727>), but refs are not. Not even when updated via `git update-ref`.

## Detecting corruption

When viewing a project's main site returns 5xx errors, it might have been a victim of corruption. To confirm, double check with [this dashboard](https://log.gprd.gitlab.net/goto/5d10a91b65b89df295d089f1bd5f8a52) (internal). If it appears there, it has at least parts of it's refs corrupted.
Any curruption will only surface here if there is traffic on that project. It might get picked up during housekeeping, but then additional damage is done.

## Before you start

Once you found the gitaly shard and location of the project on that shard (`/var/opt/gitlab/git-data/repositories/@hashed/*.git`) **`cd` into that directory**.

Make a backup! Usually (once in the repo) `sudo tar cvf ~<your user>/<issue_id>.tar .` is enough.

Example:

```
sudo tar cvf ~t4cc0re/1234.tar .
```

- There is no `/` between `~` and the username. This will expand to your user's `$HOME`.
- The file will be saved as root, but your user should be able to access it regardless.

## Identifying the damage

Any of these commands writing to the repo must be prefixed with `sudo -u git`, or you might corrupt filesystem permissions!

`git fsck` will provide you with an outline of the damage.

```
Checking object directories: 100% (256/256), done.
Checking objects: 100% (1206/1206), done.
error: refs/keep-around/0123456789abcdef0123456789abcdef01234567: invalid sha1 pointer 0000000000000000000000000000000000000000
error: refs/merge-requests/42/merge: invalid sha1 pointer 0000000000000000000000000000000000000000
error: refs/pipelines/123456789: invalid sha1 pointer 0000000000000000000000000000000000000000
error: refs/tags/v1.1: invalid sha1 pointer 0000000000000000000000000000000000000000
error: refs/heads/master: invalid sha1 pointer 0000000000000000000000000000000000000000
dangling commit 76543210fedcba9876543210fedcba9876543210
```

Not all of those errors must appear, but this is what you may expect. Anything complaining about an `invalid sha1 pointer` is a corrupted ref. A `dangling commit` can be caused by a corrupt ref, but can also be a leftover. If you see one, this is not immediately alarming.

## Types of refs and how to fix them

Luckily, we have some redundancy in state, so we can manually piece together the state the project should have been in (for the most part).

The portion on figuring out which hash should be restored follows below, but first how to restore a ref once you know the commit.

To restore a ref you should first use `git update-ref`.

```bash
sudo -u git git update-ref refs/keep-around/0123456789abcdef0123456789abcdef01234567 0123456789abcdef0123456789abcdef01234567
```

If this fails for some reason, you can update the ref using an editor of your choice run `sudo -u git $EDITOR $REF` and replace all file content with the full commit hash and a trailing newline.

Like this

```bash
sudo -u git $EDITOR refs/keep-around/0123456789abcdef0123456789abcdef01234567
```

```
0123456789abcdef0123456789abcdef01234567
```

**Important** make sure you copy the **full** commit hash (`0123456789abcdef0123456789abcdef01234567`) in all cases, not the shortened one (`012345678`).

### Check `packed-refs` first

In most cases, a quick look in `packed-refs` (if it exists) will yield the commit-hash you seek. It should look something like this:

```
0123456789abcdef0123456789abcdef01234567 refs/heads/master
0123456789abcdef0123456789abcdef01234567 refs/tags/v1.1
```

**Note**: These refs are captured at the time of a `git gc` as run in housekeeping. So they might not be up to date or not exist at all. Feel free to use this to restore the primary branch to a known good state, and then continue to look for the most recent commit.

### `refs/heads/*`

These are user-facing branches of the project. In case of the project's primary branch (such as `main` or `master`), this has to be recreated first for GitLab (and various `git` commands) to function properly.

Unfortunately, this is also the type of ref we have the least redundancy for. Your best bet here is to use the project's activity feed (visit `https://gitlab.com/<namespace>/<project>/activity` via your admin account) to get some info here. Individual links to commits on that page might fail to load, so copy their link which will contain the full hash to the commit.

In the case of the primary branches it is most important to have the ref pointing to *something* that is in the tree, rather than getting the latest commit (which you might only be able to piece together afterwards). Use your best judgement here to point it to a commit that was definitely on the branch you restore. You can validate this afterwards by running a `git log`. It should print no errors and a continous tree of commits.

Because git is decentralized, we can ask the customer to re-push those refs afterwards, but only if we did not advance the branch ahead of what it should be. The high turnaround time is also to be considered for this approach.

The nature of the filesystem corruption around refs is, that those refs are getting updated. So the likelyhood of this corruption appearing near a merge is high. Make sure to check all recently merged merge requests to piece together the most recent state of the branch. If in doubt, talk to the customer and have them double-check.

If you are unable to find a commit, you can take the nuclear option and force a lookup in all objects to find the root commit (initial commit).

Alternatively, if you saw dangling commits during `git fsck` it is very likely, that one of them is the commit you are looking for. So check it for plausibility with `git show <hash>`

#### Finding root commits with broken refs

**Regardless which approach you take** you might end up with multiple root commits (if there was an orphaned branch created for example). In those cases, you can use `git log <hash>` to see if there are any indicators for the commit belonging to another ref, that was not destroyed.
Once you restored the primary branch with the root commit, things will start working again, but you **must** to make an effort to find the latest commit for that branch, once context is available.

##### Approach 1

Git's fsck can probably find the root for us.

```bash
git fsck --root
```

```
root 17f4d6097a063c78f16e6a31e41e0e7ba753228e
[...]
```

##### Approach 2

If this fails, there is a good chance we can still reach all commits, so we can probably use `git cat-file` to find it.

```bash
git cat-file --batch-all-objects --batch-check | awk '$2~/commit/ {print $1}' | xargs -rn1 git rev-list --max-parents=0 | sort -Vu
```

##### Approach 3

If this fails, we need to bypass git.
To do this, we need to first unpack any `.pack` files. No data is being lost, we just unpack it, so we can work around git.

```bash
# git will refuse to unpack anything already present, so we need to move the pack files to a non standard location first.
# https://git-scm.com/docs/git-unpack-objects
mv objects/pack pack
# unpack all packed files (this might take a while)
find . -name '*.pack' -type f -exec sh -c 'cat {} | sudo -u git git unpack-objects' \;
# find root commit (this might also take a while)
find objects/ -type f | tr -d '/' | sed 's/objects//g' | xargs -rn1 git rev-list --max-parents=0 | sort -Vu
```

### `refs/keep-around/*`

These refs are the easiest to repair, as they contain what they should point to right in their name.
For example, `refs/keep-around/0123456789abcdef0123456789abcdef01234567` should point to `0123456789abcdef0123456789abcdef01234567`.

### `refs/merge-requests/*/merge`

These refs are bound to merge requests. So look up the merge request ID from the ref (`refs/merge-requests/42/merge` would be MR `42`) and check for the latest commit added there.

### `refs/tags/v1.1`

These refs are bound to tags. You probably want to use the project's tag list (visit `https://gitlab.com/<namespace>/<project>/-/tags` via your admin account) or releases (visit `https://gitlab.com/<namespace>/<project>/-/releases` via your admin account) to find the commit.

### `refs/pipelines/123456789`

These refs are bound to a pipeline. Look up the CI pipeline ID from the ref (`refs/pipelines/123456789` would be pipeline ID `123456789`). Then click any job, and on the right-hand side you will see the commit it is targeting.

## Oh no! There are missing commits after a garbage collection

First of all, keep calm. :) We **should** still have it in snapshots.

Running a garbage collection on git (as done by housekeeping) may remove `dangling commits`. Those are commits that do not belong to any ref. But because these refs are what might be currupted, it may happen, that git thinks an object is no longer required, but still is. It just could not see it, because ther was no ref referencing it at that point in time.

This means you will need to find the disk-level snapshots as close to the point before and after the cause of the data corruption.

Once those are identified:

- create another VM and attach those snapshots to it
- naviage to the repo on each of the snapshots
- grab a copy of the contents of their `objects` directory. (*hint* on the earlier snapshot you can also see a vaild ref for the primary branch, if required)
- merge the contents of both `objects` directories into the `objects` directory you want to restore. Do **not** overwrite files. If they are there, they are probably fine.

### What did we just do?

We supplied git with any object it had before. While this is inefficient, as the `.pack` files in the more recent repo have most of the objects packed into them, this also provides a fallback for git to retrieve the objects, that were previously lost.
This is fine, as a garbage collection run later on will just clean up anything that it does not need anymore.
