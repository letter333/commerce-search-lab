FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.7

RUN bin/elasticsearch-plugin install --batch analysis-nori

COPY --chmod=755 local-entrypoint.sh /usr/local/bin/local-entrypoint.sh
