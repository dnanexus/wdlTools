rm -Rf corpora
mkdir corpora
jq '.corpora | .[] | "cd corpora && git clone \(.url) && cd \(.url | split("/")[4] | split(".")[0]) && git checkout \(.tag)"' corpora_repos.json | xargs -i -t bash -c "{}"