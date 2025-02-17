def call(type='latest') {
  List<String> oldVersions = ['2.9.1', '2.10.0', '2.10.1', '2.11.0', '2.11.1', '2.12.0', '2.13.0', '2.14.0']
  HashMap<String, String> versions = [
    '2.15.0': 'ami-086a3a95eefa9567f',
    '2.15.1': 'ami-073928dbea8c7ebc3',
    '2.16.0': 'ami-01097b383f63f7db5',
    '2.17.0': 'ami-03af848f3557ff8d0',
    '2.18.0': 'ami-0184c7b18b45d2a7b',
    '2.19.0': 'ami-0d3c21da426d248d3',
    '2.20.0': 'ami-04ad85dd7364bba21',
    '2.21.0': 'ami-0605d9dbdc9d6a233',
    '2.22.0': 'ami-0fb9ae57bc30787cd',
    '2.23.0': 'ami-012c6702ff13e97d0',
    '2.24.0': 'ami-0e688a9b5dca3b3d2',
    '2.25.0': 'ami-09931a649be4b90e8',
    '2.26.0': 'ami-0579b750aaa578090',
    '2.27.0': 'ami-064970de413ee5144',
    '2.28.0': 'ami-015cbf0312dd101c7',
    '2.29.0': 'ami-095fc17d094642c57'
  ]
  List<String> versionsList = new ArrayList<>(versions.keySet());
  switch(type) {
    case 'latest':
      return '2.30.0'
    case 'ami':
      return versions
    case 'list':
      return versionsList
    case 'list_with_old':
      return oldVersions + versionsList
  }
}
