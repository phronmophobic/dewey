# Dewey

Index of Clojure libraries available on github.

## Rationale

The goal of this project is to make the clojure libraries available on github easier to programmatically list and inspect.

Deps.edn can procure dependencies directly from github. However, finding clojure libraries that are available via github can be more difficult compared to clojars. Clojars provides several [data endpoints](https://github.com/clojars/clojars-web/wiki/Data) to list available libraries and metadata. Even though similar info is available from github, it's not quite as easy to obtain.

## Getting the data

Pre-retrieved data can be found at [releases](https://github.com/phronmophobic/dewey/releases).

### What's included?

Each release includes the following files in .gz or tar.gz format:

- `deps-libs.edn`: This the best place to start if you're using the data. It's a map of library name to library info for all clojure github libraries that have `deps.edn` files on their default branch.
- `deps` directory: The `deps.edn` file for every clojure library that has a `deps.edn` file on their default branch. The folder structure is `deps/<github username>/<github project>/deps.edn`.
- `all-repos.edn`: A vector of all clojure repositories on github that were found (including non deps.edn based projects).
- `deps-tags.edn`: This an intermediate file of pairs of github repo information and github tag information.

All the `.edn` files can be read using something like the following:
```clojure
(require '[clojure.java.io :as io]
         '[clojure.edn :as edn])
(defn read-edn [fname]
  (with-open [rdr (io/reader fname)
              rdr (java.io.PushbackReader. rdr)]
    (edn/read rdr)))
```

### Generating the dataset via the github API

To retrieve the data yourself, follow [step 0](#0.-authentication) and then run:

```bash
# creates releases/yyyy-MM-dd/all-repos.edn
clojure -X:update-clojure-repo-index

# downloads all deps files to releases/yyyy-MM-dd/<user>/<project>/deps.edn
# due to rate limits, takes around 3 hours (mostly sleeping).
clojure -X:download-deps

# downloads tags for each deps.edn clojure library to releases/yyyy-MM-dd/deps-tags.edn
clojure -X:update-tag-index

# creates an index of library name to library metadata in releases/yyyy-MM-dd/deps-libs.edn
clojure -X:update-available-git-libs-index
```

These commands must be run in order.

## Finding Clojure Libraries Methodology

Github search is quirky and has certain limitations imposed by rate-limiting. Below is a short synopsis of how Dewey attempts to locate clojure projects on github within the limitations imposed by github's API.

### Current Method

0. Authentication
1. Find all clojure repositories
2. Download all deps.edn files

#### 0. Authentication

Dewey uses personal access tokens to make github API requests. You can obtain a personal access token by following [these docs](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token).

Once you have obtained your personal access token, save it to an edn file called "secrets.edn" in the root project directly using following format:

```edn
{:github {:user "my-username"
          :token "my-token"}}
```

#### 1. Find all clojure libraries

Currently, the first step is to paginate through the results of the github repository search `language:clojure` sorted by stars in descending order. There's a 1,000 result limit for any specific search so after exhausting the results from `language:clojure`, we find repositories for specific numbers of stars starting at the star number from the last result. The search query for these requests look like `language:clojure stars:123`, `language:clojure stars:122`, etc.

#### 2. Download all deps.edn files

Once we have a list of clojure github repositories, we can then check each repository for its `deps.edn` file. Given a repository, the url for the deps.edn file looks like `(str "https://raw.githubusercontent.com/" full-name "/" default-branch "/" fname)))`.

#### Current known limitations

- There are some libraries that actually are clojure libraries, but aren't found when searching using `language:clojure`
- Clojurescript only libraries are not currently targeted
- Only checks tip of default branch.
- Only 1,000 libraries max per star count. At the time of writing, this only matters for star counts less than 5.

### Failed Strategies

#### Searching github code with "filename:deps.edn"

I thought just asking github for all the files named deps.edn might work. The roadblocks I ran into were:
1. Hitting [secondary rate](https://docs.github.com/en/rest/overview/resources-in-the-rest-api#secondary-rate-limits) after 1-2 requests.
2. Receiving only 0-3 results even on successful requests.


### Alternative Strategies

These are stategies that I didn't try, but might be good alternatives if the main strategy fails.

#### Scanning clojure repos by created or updated

As suggested by [this](https://stackoverflow.com/a/37639739) stackoverflow answer, you can search by a field. The search API currently limits results to a max of 1000, but if you search a small enough window of time, you can scan through all the libraries.

[Relevant Github docs](https://docs.github.com/en/search-github/searching-on-github/searching-for-repositories#search-based-on-when-a-repository-was-created-or-last-updated)

#### Use github's GraphQL API

It's possible that github's [GraphQL API](https://docs.github.com/en/graphql) might provide opportunities for improvement. However, it doesn't appear to have a way to filter by language or any other means of identifying repositories that are clojure related.

## Future Work

Now that we've bothered to catalog of all of the clojure repos on github, there's several interesting projects we can do that use the data:
- Download and run static analysis across repos
- Create a website that combines the clojars data API with dewey's data to make it easier to search for clojure libraries.
- Integrate the data into tools and IDEs
  - deps.edn editor that knows the available libraries and versions
  - Find example usages for libraries or specific functions (for [example](https://github.com/phronmophobic/add-deps))
- Add support for other git hosting sites like gitlab.


## License

Copyright ?? 2022 Adrian

Distributed under the Eclipse Public License version 1.0.
