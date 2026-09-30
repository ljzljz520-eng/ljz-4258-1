export default {
  server: { port: 5173, proxy: { '/auth': 'http://127.0.0.1:8000', '/arrivals': 'http://127.0.0.1:8000', '/unloads': 'http://127.0.0.1:8000', '/topups': 'http://127.0.0.1:8000', '/sync': 'http://127.0.0.1:8000', '/samples': 'http://127.0.0.1:8000', '/lab': 'http://127.0.0.1:8000', '/releases': 'http://127.0.0.1:8000', '/reconciliation': 'http://127.0.0.1:8000', '/devices': 'http://127.0.0.1:8000', '/lineage': 'http://127.0.0.1:8000', '/conservation': 'http://127.0.0.1:8000' } }
}
